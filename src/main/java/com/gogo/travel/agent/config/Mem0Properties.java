package com.gogo.travel.agent.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("agentscope.memory.mem0")
public record Mem0Properties(boolean enabled, String apiBaseUrl, String apiKey,
                             String apiType, Duration timeout, String agentName) {
    public Mem0Properties {
        apiBaseUrl = apiBaseUrl == null ? "http://127.0.0.1:8888" : apiBaseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        apiType = apiType == null ? "self-hosted" : apiType;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        agentName = agentName == null ? "corporate-travel-platform" : agentName;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Mem0 timeout must be positive");
    }
}
