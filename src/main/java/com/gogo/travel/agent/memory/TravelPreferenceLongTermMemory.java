package com.gogo.travel.agent.memory;
import io.agentscope.core.memory.LongTermMemory;
import com.gogo.travel.agent.config.Mem0Properties;
import io.agentscope.core.memory.mem0.*;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Mem0 owns durable storage. Query-dependent results must not be cached solely by user ID. */
public class TravelPreferenceLongTermMemory implements LongTermMemory {
    private final LongTermMemory delegate;
    private final Duration timeout;
    private final String userId;
    private final String agentName;
    private final Mem0Client client;
    public TravelPreferenceLongTermMemory(Mem0Properties properties, String userId) {
        this.timeout = properties.timeout(); this.userId = userId; this.agentName = properties.agentName();
        var type = Mem0ApiType.fromString(properties.apiType());
        var key = properties.apiKey().isBlank() ? null : properties.apiKey();
        this.client = new Mem0Client(properties.apiBaseUrl(), key, type, timeout);
        this.delegate = Mem0LongTermMemory.builder().agentName(agentName).userId(userId)
            .apiBaseUrl(properties.apiBaseUrl()).apiKey(key).apiType(type).timeout(timeout).build();
    }
    @Override public Mono<Void> record(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return Mono.empty();
        return Mono.defer(() -> delegate.record(messages)).timeout(timeout);
    }
    public Mono<String> retrieveStrict(Msg query) {
        if (query == null || query.getTextContent() == null || query.getTextContent().isBlank()) return Mono.just("");
        // AgentScope's high-level retrieve suppresses HTTP errors. Use its typed client here
        // so preference reads distinguish an empty memory from an unavailable service.
        return Mono.defer(() -> client.search(Mem0SearchRequest.builder().query(query.getTextContent())
                .userId(userId).agentId(agentName).topK(20).build()))
            .map(response -> response.getResults() == null ? "" : response.getResults().stream()
                .map(Mem0SearchResult::getMemory).filter(Objects::nonNull).collect(Collectors.joining("\n")))
            .timeout(timeout);
    }
    @Override public Mono<String> retrieve(Msg query) {
        // Conversations may continue without memory; the preferences API uses strict mode.
        return retrieveStrict(query).onErrorReturn("");
    }
    public String getUserId() { return userId; }
}
