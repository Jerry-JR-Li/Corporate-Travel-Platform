package com.gogo.travel.observability;
import com.alibaba.fastjson2.JSON;
import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.config.sensitive.SensitiveMasker;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatUsage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Adds Langfuse OTLP attributes. Content export is opt-in, bounded and redacted before export. */
@Component
public class LangfuseObservations {
    private final boolean enabled;
    private final boolean captureContent;
    private final String environment;
    private static final Pattern SECRET_FIELD=Pattern.compile("(?i).*(password|secret|token|api.?key|authorization|id.?number|phone|email).*");
    public LangfuseObservations(@Value("${app.langfuse.enabled:false}") boolean enabled,
            @Value("${app.langfuse.capture-content:false}") boolean captureContent,
            @Value("${app.langfuse.environment:local}") String environment) {
        this.enabled=enabled;this.captureContent=captureContent;this.environment=environment;
    }
    public void start(AgentObservability.SpanHandle span, AgentSessionContext context, String type, String model, Object input) {
        if(!enabled || span==null) return;
        span.tag("langfuse.observation.type",type).tag("langfuse.trace.name","corporate-travel")
            .tag("langfuse.session.id",context.getSessionId()).tag("langfuse.user.id",context.getUserId())
            .tag("langfuse.environment",environment).tag("langfuse.observation.metadata.runId",context.getRunId());
        if(model!=null) span.tag("langfuse.observation.model.name",model);
        payload(span,"input",input);
    }
    public void finish(AgentObservability.SpanHandle span, Object output, ChatUsage usage) {
        if(!enabled || span==null) return;
        payload(span,"output",output);
        if(usage!=null) span.tag("langfuse.observation.usage_details",JSON.toJSONString(Map.of(
            "input",usage.getInputTokens(),"output",usage.getOutputTokens(),"total",usage.getTotalTokens())));
    }
    public void error(AgentObservability.SpanHandle span, Throwable error) {
        if(!enabled || span==null) return;
        span.tag("langfuse.observation.level","ERROR")
            .tag("langfuse.observation.status_message",error.getClass().getSimpleName());
    }
    private void payload(AgentObservability.SpanHandle span,String field,Object value) {
        if(!captureContent || value==null) return;
        String json=JSON.toJSONString(sanitize(value,0));
        if(json.length()>16000) {
            json=JSON.toJSONString(Map.of("preview",json.substring(0,15000),"truncated",true));
            span.tag("langfuse.observation.metadata."+field+"Truncated",true);
        }
        span.tag("langfuse.observation."+field,json);
    }
    Object sanitize(Object value,int depth) {
        if(value==null) return null;
        if(depth>8) return "[depth limit]";
        if(value instanceof Msg msg) return Map.of("role",msg.getRole().name().toLowerCase(),
            "content",sanitize(msg.getContent(),depth+1));
        if(value instanceof TextBlock text) return clean(text.getText());
        if(value instanceof ToolUseBlock tool) return Map.of("name",tool.getName(),"arguments",sanitize(tool.getInput(),depth+1));
        if(value instanceof ToolResultBlock result) return sanitize(result.getOutput(),depth+1);
        if(value instanceof ContentBlock) return "[non-text block omitted]";
        if(value instanceof Map<?,?> map) {
            Map<String,Object> out=new LinkedHashMap<>();
            map.entrySet().stream().limit(100).forEach(e -> out.put(String.valueOf(e.getKey()),
                SECRET_FIELD.matcher(String.valueOf(e.getKey())).matches() ? "[REDACTED]" : sanitize(e.getValue(),depth+1)));
            return out;
        }
        if(value instanceof Collection<?> list) return list.stream().limit(100).map(v->sanitize(v,depth+1)).toList();
        if(value instanceof Number || value instanceof Boolean) return value;
        return clean(String.valueOf(value));
    }
    private String clean(String text) {
        if(text==null) return "";
        String masked=SensitiveMasker.mask(text.replaceAll("(?i)(pub_|pk-lf-|sk-lf-|m0sk_)[A-Za-z0-9_-]+","[REDACTED]")
            .replaceAll("(?i)(Bearer|Basic)\\s+[A-Za-z0-9_+./=-]+", "$1 [REDACTED]"));
        return masked.length()>8000 ? masked.substring(0,8000)+"[truncated]" : masked;
    }
}
