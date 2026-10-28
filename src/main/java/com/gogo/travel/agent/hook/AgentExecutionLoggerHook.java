package com.gogo.travel.agent.hook;

import com.gogo.travel.agent.context.AgentSessionContext;
import com.gogo.travel.agent.context.SessionReactorContext;
import com.gogo.travel.observability.AgentObservability;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.micrometer.tracing.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Logs AgentScope lifecycle events and mirrors them as child OTel spans. */
public class AgentExecutionLoggerHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionLoggerHook.class);

    private final AgentObservability observability;
    private final Map<String, AgentObservability.SpanHandle> callSpans = new ConcurrentHashMap<>();
    private final Map<String, AgentObservability.SpanHandle> reasoningSpans = new ConcurrentHashMap<>();
    private final Map<String, AgentObservability.SpanHandle> toolSpans = new ConcurrentHashMap<>();
    private final Map<String, Long> toolStartNanos = new ConcurrentHashMap<>();

    /** Kept for source compatibility with tests and older configuration code. */
    public AgentExecutionLoggerHook() {
        this(new AgentObservability());
    }

    public AgentExecutionLoggerHook(AgentObservability observability) {
        this.observability = observability == null ? new AgentObservability() : observability;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        String agentName = getAgentName(event);
        return Mono.deferContextual(view -> {
            AgentSessionContext context = SessionReactorContext.from(view);
            if (context == null) {
                logWithoutContext(event, agentName);
                return Mono.just(event);
            }

            String runId = context.getRunId();
            String agentKey = agentKey(event);
            Span runSpan = SessionReactorContext.runSpan(view);

            if (event instanceof PreCallEvent preCall) {
                logger.info("[AGENT][{}][runId={}] PreCall inputMessages={}", agentName, runId,
                        preCall.getInputMessages().size());
                observability.recordCall(agentName);
                put(callSpans, key(runId, agentKey), observability.start(
                        "agent.call", runSpan, attrs(context, Map.of(
                                "agent.name", safe(agentName),
                                "agent.message_count", preCall.getInputMessages().size()))));
                observability.langfuse().start(callSpans.get(key(runId, agentKey)), context, "agent", null, preCall.getInputMessages());
                if (logger.isDebugEnabled()) {
                    logMessages(preCall.getInputMessages(), agentName);
                }
            } else if (event instanceof PostCallEvent postCall) {
                Msg finalMessage = postCall.getFinalMessage();
                logger.info("[AGENT][{}][runId={}] PostCall responseLength={}, reason={}", agentName, runId,
                        textLength(finalMessage), finalMessage == null ? null : finalMessage.getGenerateReason());
                AgentObservability.SpanHandle span = callSpans.remove(key(runId, agentKey));
                observability.langfuse().finish(span, finalMessage, null);
                if (span != null) {
                    if (finalMessage != null && finalMessage.getGenerateReason() != null) {
                        span.tag("agent.generate_reason", finalMessage.getGenerateReason());
                    }
                    span.end();
                }
            } else if (event instanceof PreReasoningEvent preReasoning) {
                java.util.List<Msg> inputs = preReasoning.getInputMessages();
                String modelName = safe(preReasoning.getModelName());
                logger.info("[AGENT][{}][runId={}] PreReasoning inputMessages={}, model={}", agentName, runId,
                        inputs.size(), modelName);
                observability.recordReasoning(agentName, modelName);
                AgentObservability.SpanHandle parent = callSpans.get(key(runId, agentKey));
                put(reasoningSpans, key(runId, agentKey), observability.start(
                        "agent.reasoning", parent == null ? runSpan : parent.span(), attrs(context, Map.of(
                                "agent.name", safe(agentName),
                                "llm.model", modelName,
                                "agent.message_count", inputs.size()))));
                observability.langfuse().start(reasoningSpans.get(key(runId, agentKey)), context, "generation", modelName, inputs);
                if (logger.isDebugEnabled()) {
                    logReasoningMessages(inputs, agentName);
                }
            } else if (event instanceof PostReasoningEvent postReasoning) {
                Msg reasoning = postReasoning.getReasoningMessage();
                logger.info("[AGENT][{}][runId={}] PostReasoning responseLength={}", agentName, runId,
                        textLength(reasoning));
                AgentObservability.SpanHandle span = reasoningSpans.remove(key(runId, agentKey));
                observability.langfuse().finish(span, reasoning, reasoning == null ? null : reasoning.getChatUsage());
                if (span != null) {
                    if (reasoning != null && reasoning.getGenerateReason() != null) {
                        span.tag("llm.finish_reason", reasoning.getGenerateReason());
                    }
                    span.end();
                }
                if (logger.isDebugEnabled() && reasoning != null) {
                    logger.debug("[AGENT][{}] reasoning={}", agentName, reasoning.getTextContent());
                }
            } else if (event instanceof PreActingEvent preActing && preActing.getToolUse() != null) {
                ToolUseBlock toolUse = preActing.getToolUse();
                logger.info("[AGENT][{}][runId={}] PreActing toolName={}, toolUseId={}",
                        agentName, runId, toolUse.getName(), toolUse.getId());
                AgentObservability.SpanHandle parent = callSpans.get(key(runId, agentKey));
                AgentObservability.SpanHandle span = observability.start(
                        "agent.tool", parent == null ? runSpan : parent.span(), attrs(context, Map.of(
                                "agent.name", safe(agentName),
                                "tool.name", safe(toolUse.getName()),
                                "tool.id", safe(toolUse.getId()))));
                observability.langfuse().start(span, context, "tool", null, toolUse.getInput());
                put(toolSpans, key(runId, safe(toolUse.getId())), span);
                toolStartNanos.put(key(runId, safe(toolUse.getId())), System.nanoTime());
                observability.registerActiveTool(runId, span);
                if (logger.isDebugEnabled()) {
                    logger.debug("[AGENT][{}] toolInput={}", agentName, toolUse.getInput());
                }
            } else if (event instanceof PostActingEvent postActing && postActing.getToolUse() != null) {
                ToolUseBlock toolUse = postActing.getToolUse();
                ToolResultBlock toolResult = postActing.getToolResult();
                boolean suspended = toolResult != null && toolResult.isSuspended();
                logger.info("[AGENT][{}][runId={}] PostActing toolName={}, toolUseId={}, suspended={}",
                        agentName, runId, toolUse.getName(), toolUse.getId(), suspended);
                AgentObservability.SpanHandle span = toolSpans.remove(key(runId, safe(toolUse.getId())));
                observability.langfuse().finish(span, toolResult, null);
                Long started = toolStartNanos.remove(key(runId, safe(toolUse.getId())));
                long durationMillis = started == null
                        ? 0L : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (span != null) {
                    span.tag("tool.suspended", suspended);
                    if (toolResult != null && toolResult.getOutput() != null) {
                        span.tag("tool.result_blocks", toolResult.getOutput().size());
                    }
                    observability.clearActiveTool(runId, span);
                    span.end();
                }
                observability.recordTool(agentName, toolUse.getName(), suspended ? "suspended" : "success", durationMillis);
                if (suspended) {
                    observability.recordSuspended(agentName);
                }
                if (logger.isDebugEnabled() && toolResult != null) {
                    logger.debug("[AGENT][{}] toolResult output size={}", agentName,
                            toolResult.getOutput() == null ? 0 : toolResult.getOutput().size());
                }
            } else if (event instanceof ErrorEvent error) {
                String prefix = runId + ":";
                for (Map<String, AgentObservability.SpanHandle> spans : java.util.List.of(reasoningSpans, toolSpans, callSpans)) {
                    spans.forEach((id, span) -> {
                        if (id.startsWith(prefix) && spans.remove(id, span)) {
                            observability.langfuse().error(span, error.getError());
                            span.error(error.getError()).end();
                            observability.clearActiveTool(runId, span);
                        }
                    });
                }
                toolStartNanos.keySet().removeIf(id -> id.startsWith(prefix));
            } else {
                logWithoutContext(event, agentName);
            }
            return Mono.just(event);
        });
    }

    private void logWithoutContext(HookEvent event, String agentName) {
        if (event instanceof PreCallEvent preCall) {
            logger.info("[AGENT][{}] PreCall inputMessages={}", agentName, preCall.getInputMessages().size());
        } else if (event instanceof PreReasoningEvent preReasoning) {
            logger.info("[AGENT][{}] PreReasoning inputMessages={}", agentName, preReasoning.getInputMessages().size());
        } else if (event instanceof PostCallEvent) {
            logger.info("[AGENT][{}] PostCall", agentName);
        }
    }

    private String getAgentName(HookEvent event) {
        Agent agent = event.getAgent();
        return agent != null ? agent.getName() : "unknown";
    }

    private String agentKey(HookEvent event) {
        Agent agent = event.getAgent();
        if (agent == null) {
            return "unknown";
        }
        return safe(agent.getAgentId()) + ":" + safe(agent.getName());
    }

    private Map<String, Object> attrs(AgentSessionContext context, Map<String, Object> values) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("agent.run_id", context.getRunId());
        attributes.put("agent.session_id", context.getSessionId());
        attributes.putAll(values);
        return attributes;
    }

    private void put(Map<String, AgentObservability.SpanHandle> map, String key,
                     AgentObservability.SpanHandle span) {
        AgentObservability.SpanHandle previous = map.put(key, span);
        if (previous != null) {
            previous.end();
        }
    }

    private String key(String runId, String suffix) {
        return safe(runId) + ":" + safe(suffix);
    }

    private int textLength(Msg message) {
        return message == null || message.getTextContent() == null ? 0 : message.getTextContent().length();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private void logMessages(java.util.List<Msg> messages, String agentName) {
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            logger.debug("[AGENT][{}] msg[{}] role={} chars={}", agentName, i,
                    msg.getRole(), renderedLength(msg));
        }
    }

    private void logReasoningMessages(java.util.List<Msg> messages, String agentName) {
        int totalChars = 0;
        for (Msg msg : messages) {
            totalChars += renderedLength(msg);
        }
        logger.debug("[AGENT][{}] LLM input messageCount={}, totalChars={}", agentName,
                messages.size(), totalChars);
    }

    private int renderedLength(Msg msg) {
        if (msg == null) {
            return 0;
        }
        java.util.List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return msg.getTextContent() == null ? 0 : msg.getTextContent().length();
        }
        int length = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                length += text.getText() == null ? 0 : text.getText().length();
            } else {
                length += String.valueOf(block).length();
            }
        }
        return length;
    }
}
