package com.gogo.travel.agent.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * @author Hollis
 */
@Configuration
public class ModelConfig {

    @Value("${agentscope.dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    @Value("${agentscope.dashscope.models.fast}")
    private String fastModelName;

    @Value("${agentscope.dashscope.models.strong}")
    private String strongModelName;

    @Value("${agentscope.dashscope.models.stable}")
    private String stableModelName;

    @Bean("fastModel")
    public Model fastModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // 1.2元/百万token ，或者用最便宜的 qwen-flash 也够了，0.15元/百万token
                .modelName(fastModelName)
                .stream(true)
                .enableThinking(false)
                .build();
    }

    @Bean("strongModel")
    @Primary
    public Model strongModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // 12元/百万token  或者用最新的 kimi/kimi-k3 20元/百万token
                .modelName(strongModelName)
                .stream(true)
                .enableThinking(false)
                .build();
    }

    @Bean("strongModelWithThinking")
    public Model strongModelWithThinking() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                // 12元/百万token  或者用最新的 kimi/kimi-k3 20元/百万token
                .modelName("qwen3.7-max")
                .stream(true)
                // 开启深度思考
                .enableThinking(true)
                .build();
    }

    @Bean("stableModel")
    public Model stableModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                //6元/百万token
                .modelName(stableModelName)
                .stream(true)
                .enableThinking(false)
                .build();
    }
}
