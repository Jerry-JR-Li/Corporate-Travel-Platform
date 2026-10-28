package com.gogo.travel.agent.memory;
import com.gogo.travel.agent.config.Mem0Properties;
import io.agentscope.core.memory.LongTermMemory;

/** Application namespace and authenticated user ID isolate memories across users/projects. */
public class TravelPreferenceLongTermMemoryFactory {
    private final Mem0Properties properties;
    public TravelPreferenceLongTermMemoryFactory(Mem0Properties properties) { this.properties = properties; }
    public LongTermMemory create(String userId) {
        if (!properties.enabled() || userId == null || userId.isBlank()) return null;
        return new TravelPreferenceLongTermMemory(properties, userId);
    }
}
