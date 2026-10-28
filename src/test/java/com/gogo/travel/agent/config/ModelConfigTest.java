package com.gogo.travel.agent.config;

import io.agentscope.core.model.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelConfigTest {

    @Test
    void modelBeansUseConfiguredModelNames() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "agentscope.dashscope.api-key=test-key",
                    "agentscope.dashscope.models.fast=test-fast-model",
                    "agentscope.dashscope.models.strong=test-strong-model",
                    "agentscope.dashscope.models.stable=test-stable-model"
            ).applyTo(context);
            context.register(ModelConfig.class);
            context.refresh();

            assertEquals("test-fast-model",
                    context.getBean("fastModel", DashScopeChatModel.class).getModelName());
            assertEquals("test-strong-model",
                    context.getBean("strongModel", DashScopeChatModel.class).getModelName());
            assertEquals("test-stable-model",
                    context.getBean("stableModel", DashScopeChatModel.class).getModelName());
        }
    }
}
