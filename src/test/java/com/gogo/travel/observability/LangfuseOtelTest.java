package com.gogo.travel.observability;

import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.*;
import com.gogo.travel.agent.hook.AgentExecutionLoggerHook;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangfuseOtelTest {
    final List<SpanData> exported = new CopyOnWriteArrayList<>();
    final SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
        public CompletableResultCode export(Collection<SpanData> spans) { exported.addAll(spans); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    })).build();
    final AgentObservability telemetry = new AgentObservability();
    final AgentSessionContext context = new AgentSessionContext("alice","session-test");
    final AgentExecutionLoggerHook hook = new AgentExecutionLoggerHook(telemetry);
    final Agent agent = mock(Agent.class);
    AgentObservability.SpanHandle run;
    LangfuseOtelTest() {
        telemetry.setTracer(new OtelTracer(provider.get("test"),new OtelCurrentTraceContext(),event -> {}));
        telemetry.setLangfuse(new LangfuseObservations(true,true,"test"));
        when(agent.getName()).thenReturn("travel"); when(agent.getAgentId()).thenReturn("agent-1");
    }
    @AfterEach void close() { provider.close(); }
    String tag(SpanData s,String key) { return s.getAttributes().get(AttributeKey.stringKey(key)); }
    SpanData span(String name) { return exported.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow(); }
    void fire(HookEvent event) { hook.onEvent(event).contextWrite(SessionReactorContext.of(context,false,run.span())).block(); }
    Msg message(String text) { return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build(); }

    @Test void hooksExportParentHierarchyUsageAndRedactedContent() {
        run=telemetry.startRun(context,"chat",false);
        var input=List.of(message("email test@example.com phone 13800138000 api_key=private-input"));
        fire(new PreCallEvent(agent,input)); fire(new PreReasoningEvent(agent,"qwen-test",null,input));
        var output=Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("answer").build())
            .metadata(Map.of(MessageMetadataKeys.CHAT_USAGE,new ChatUsage(123,45,0.5))).build();
        fire(new PostReasoningEvent(agent,"qwen-test",null,output));
        var toolkit=new Toolkit();
        var tool=ToolUseBlock.builder().id("tool-1").name("search").input(Map.of("query","hotel","password","private-tool")).build();
        fire(new PreActingEvent(agent,toolkit,tool));
        fire(new PostActingEvent(agent,toolkit,tool,new ToolResultBlock("tool-1","search",
            List.of(TextBlock.builder().text("Authorization: Bearer private-bearer sk-lf-private-credential").build()))));
        fire(new PostCallEvent(agent,output)); telemetry.endRun(context.getRunId(),run,"completed",10);
        assertThat(exported).hasSize(4);
        var root=span("agent.run"); var call=span("agent.call"); var generation=span("agent.reasoning"); var toolSpan=span("agent.tool");
        assertThat(call.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(generation.getParentSpanId()).isEqualTo(call.getSpanId());
        assertThat(toolSpan.getParentSpanId()).isEqualTo(call.getSpanId());
        assertThat(exported).allSatisfy(s -> {
            assertThat(s.getTraceId()).isEqualTo(root.getTraceId());
            assertThat(tag(s,"langfuse.session.id")).isEqualTo("session-test");
            assertThat(s.getAttributes().toString()).doesNotContain("private-input","private-tool","private-bearer","private-credential","test@example.com","13800138000");
        });
        assertThat(tag(generation,"langfuse.observation.type")).isEqualTo("generation");
        assertThat(tag(generation,"langfuse.observation.model.name")).isEqualTo("qwen-test");
        assertThat(JSON.parseObject(tag(generation,"langfuse.observation.usage_details")))
            .containsEntry("input",123).containsEntry("output",45).containsEntry("total",168);
    }
    @Test void errorsCloseOutstandingSpansWithoutExportingExceptionSecrets() {
        run=telemetry.startRun(context,"chat",false);
        fire(new PreCallEvent(agent,List.of(message("hello"))));
        fire(new PreReasoningEvent(agent,"qwen-test",null,List.of()));
        fire(new ErrorEvent(agent,new IllegalStateException("private-exception-value")));
        telemetry.endRun(context.getRunId(),run,"failed",10);
        assertThat(exported).hasSize(3).allSatisfy(s -> {
            assertThat(tag(s,"langfuse.observation.level")).isEqualTo("ERROR");
            assertThat(s.toString()).doesNotContain("private-exception-value");
        });
    }
    @Test void disabledContentCaptureStillExportsTimingAndType() {
        telemetry.setLangfuse(new LangfuseObservations(true,false,"test"));
        run=telemetry.startRun(context,"chat",false);
        fire(new PreCallEvent(agent,List.of(message("private-content"))));
        fire(new PostCallEvent(agent,message("private-output")));
        telemetry.endRun(context.getRunId(),run,"completed",10);
        assertThat(tag(span("agent.call"),"langfuse.observation.type")).isEqualTo("agent");
        assertThat(tag(span("agent.call"),"langfuse.observation.input")).isNull();
        assertThat(tag(span("agent.call"),"langfuse.observation.output")).isNull();
    }
}
