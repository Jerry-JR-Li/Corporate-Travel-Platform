package com.gogo.travel.observability;

import com.gogo.travel.agent.context.AgentSessionContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small observability facade shared by the chat executor and AgentScope hooks.
 *
 * <p>Micrometer's tracing bridge sends these spans to OpenTelemetry. Keeping
 * this code behind a facade makes the application safe when tracing is disabled
 * (for example in a unit test), while the same lifecycle is still exported as
 * low-cardinality Prometheus metrics.</p>
 */
@Component
public class AgentObservability {

    private LangfuseObservations langfuse = new LangfuseObservations(false, false, "local");
    @Autowired public void setLangfuse(LangfuseObservations observations) { this.langfuse = observations; }
    public LangfuseObservations langfuse() { return langfuse; }
    private volatile Tracer tracer = Tracer.NOOP;
    private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Map<String, SpanHandle> runSpans = new ConcurrentHashMap<>();
    private final Map<String, SpanHandle> activeToolSpans = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public void setTracer(Tracer tracer) {
        if (tracer != null) {
            this.tracer = tracer;
        }
    }

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            this.meterRegistry = meterRegistry;
        }
    }

    public SpanHandle startRun(AgentSessionContext context, String entrypoint, boolean resumed) {
        if (context == null) {
            return SpanHandle.noop();
        }
        SpanHandle handle = start("agent.run", null, Map.of(
                "service.name", "gogo-travel-agent",
                "agent.run_id", value(context.getRunId()),
                "agent.session_id", value(context.getSessionId()),
                "agent.user_id", value(context.getUserId()),
                "agent.entrypoint", value(entrypoint),
                "agent.resumed", resumed));
        langfuse.start(handle, context, "chain", null, null);
        SpanHandle previous = runSpans.put(context.getRunId(), handle);
        if (previous != null) {
            previous.end();
        }
        counter("agent.runs.started", "Number of Agent runs started").increment();
        return handle;
    }

    public SpanHandle start(String name, Span explicitParent, Map<String, ?> attributes) {
        Span parent = explicitParent != null && !explicitParent.isNoop()
                ? explicitParent
                : currentParent();
        Span span;
        try {
            span = parent != null && !parent.isNoop() ? tracer.nextSpan(parent) : tracer.nextSpan();
        } catch (RuntimeException ignored) {
            span = Span.NOOP;
        }
        if (span == null) {
            span = Span.NOOP;
        }
        Span activeSpan = span;
        activeSpan.name(name == null || name.isBlank() ? "agent.operation" : name);
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (key != null && value != null) {
                    activeSpan.tag(key, String.valueOf(value));
                }
            });
        }
        activeSpan.start();
        return new SpanHandle(activeSpan);
    }

    public Span currentParent() {
        String runId = currentRunId();
        if (runId != null) {
            SpanHandle tool = activeToolSpans.get(runId);
            if (tool != null && !tool.span().isNoop()) {
                return tool.span();
            }
            SpanHandle run = runSpans.get(runId);
            if (run != null && !run.span().isNoop()) {
                return run.span();
            }
        }
        Span current = tracer.currentSpan();
        return current == null ? Span.NOOP : current;
    }

    public Span parentFor(String runId, Span fallback) {
        SpanHandle tool = runId == null ? null : activeToolSpans.get(runId);
        if (tool != null && !tool.span().isNoop()) {
            return tool.span();
        }
        SpanHandle run = runId == null ? null : runSpans.get(runId);
        if (run != null && !run.span().isNoop()) {
            return run.span();
        }
        return fallback == null ? Span.NOOP : fallback;
    }

    public void registerActiveTool(String runId, SpanHandle toolSpan) {
        if (runId != null && !runId.isBlank() && toolSpan != null) {
            activeToolSpans.put(runId, toolSpan);
        }
    }

    public void clearActiveTool(String runId, SpanHandle toolSpan) {
        if (runId == null) {
            return;
        }
        activeToolSpans.computeIfPresent(runId, (ignored, current) -> current == toolSpan ? null : current);
    }

    public void endRun(String runId, SpanHandle handle, String status, long durationMillis) {
        boolean owned = runId != null && runSpans.remove(runId, handle);
        if (runId != null) {
            activeToolSpans.remove(runId);
        }
        if (owned) {
            String normalized = status == null || status.isBlank() ? "completed" : status;
            if (handle != null) {
                handle.tag("agent.run.status", normalized);
                if ("failed".equals(normalized)) langfuse.error(handle, new IllegalStateException("Agent run failed"));
            }
            counter("agent.runs.completed", "Number of Agent runs completed", "status", normalized).increment();
            timer("agent.run.duration", "Agent run duration", "status", normalized)
                    .record(Math.max(0L, durationMillis), TimeUnit.MILLISECONDS);
        }
        if (handle != null) {
            handle.end();
        }
    }

    public void recordCall(String agentName) {
        counter("agent.calls", "Number of Agent call lifecycle events", "agent", safeTag(agentName)).increment();
    }

    public void recordReasoning(String agentName, String modelName) {
        counter("agent.reasoning.rounds", "Number of LLM reasoning rounds",
                "agent", safeTag(agentName), "model", safeTag(modelName)).increment();
    }

    public void recordTool(String agentName, String toolName, String status, long durationMillis) {
        String agent = safeTag(agentName);
        String tool = safeTag(toolName);
        counter("agent.tools", "Number of Agent tool calls",
                "agent", agent, "tool", tool, "status", safeTag(status)).increment();
        timer("agent.tool.duration", "Agent tool duration", "agent", agent, "tool", tool)
                .record(Math.max(0L, durationMillis), TimeUnit.MILLISECONDS);
    }

    public void recordToolError(String agentName, String toolName) {
        counter("agent.tool.errors", "Number of failed Agent tool calls",
                "agent", safeTag(agentName), "tool", safeTag(toolName)).increment();
    }

    public void recordSuspended(String agentName) {
        counter("agent.suspended", "Number of Agent runs suspended for user input",
                "agent", safeTag(agentName)).increment();
    }

    private io.micrometer.core.instrument.Counter counter(String name, String description) {
        return io.micrometer.core.instrument.Counter.builder(name)
                .description(description).register(meterRegistry);
    }

    private io.micrometer.core.instrument.Counter counter(String name, String description, String... tags) {
        io.micrometer.core.instrument.Counter.Builder builder = io.micrometer.core.instrument.Counter.builder(name)
                .description(description);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }
        return builder.register(meterRegistry);
    }

    private io.micrometer.core.instrument.Timer timer(String name, String description) {
        return io.micrometer.core.instrument.Timer.builder(name)
                .description(description).register(meterRegistry);
    }

    private io.micrometer.core.instrument.Timer timer(String name, String description, String... tags) {
        io.micrometer.core.instrument.Timer.Builder builder = io.micrometer.core.instrument.Timer.builder(name)
                .description(description);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }
        return builder.register(meterRegistry);
    }

    private String currentRunId() {
        String runId = MDC.get("agentRunId");
        return runId == null || runId.isBlank() ? null : runId;
    }

    private static String value(Object value) {
        return Objects.toString(value, "");
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    public static final class SpanHandle {
        private final Span span;
        private final AtomicBoolean ended = new AtomicBoolean();

        private SpanHandle(Span span) {
            this.span = span == null ? Span.NOOP : span;
        }

        public static SpanHandle noop() {
            return new SpanHandle(Span.NOOP);
        }

        public Span span() {
            return span;
        }

        public SpanHandle tag(String key, Object value) {
            if (key != null && value != null) {
                span.tag(key, String.valueOf(value));
            }
            return this;
        }

        public SpanHandle error(Throwable error) {
            if (error != null) {
                span.tag("error.type", error.getClass().getName());
                // Provider exceptions can contain credential-bearing URLs or request bodies.
                span.error(new RuntimeException(error.getClass().getSimpleName()));
            }
            return this;
        }

        public void end() {
            if (ended.compareAndSet(false, true)) {
                span.end();
            }
        }
    }
}
