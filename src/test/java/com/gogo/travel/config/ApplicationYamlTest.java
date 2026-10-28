package com.gogo.travel.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationYamlTest {

    @Test
    void applicationYamlIsParseable() {
        ClassPathResource applicationYaml = new ClassPathResource("application.yml");

        assertDoesNotThrow(() ->
                new YamlPropertySourceLoader().load("application", applicationYaml));
    }

    @Test
    void localInfrastructureDefaultsAreEnvironmentBacked() throws Exception {
        ClassPathResource applicationYaml = new ClassPathResource("application.yml");
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", applicationYaml)
                .getFirst();

        assertEquals("${REDIS_PORT:6381}", properties.getProperty("spring.data.redis.port"));
        assertEquals("${REDIS_USERNAME:}", properties.getProperty("spring.data.redis.username"));
        assertEquals("${REDIS_PASSWORD:}", properties.getProperty("spring.data.redis.password"));
        assertEquals("${DB_USERNAME:}", properties.getProperty("spring.datasource.username"));
        assertEquals("${DB_PASSWORD:}", properties.getProperty("spring.datasource.password"));
        assertTrue(properties.getProperty("spring.datasource.url").toString()
                .startsWith("${DB_URL:jdbc:mysql://localhost:3306/corporate_travel"));
        assertEquals("optional:classpath:application-local.yml",
                properties.getProperty("spring.config.import[2]"));
        assertEquals("${DASHSCOPE_API_KEY:}",
                properties.getProperty("agentscope.dashscope.api-key"));
        assertEquals("${DASHSCOPE_FAST_MODEL:qwen-flash}",
                properties.getProperty("agentscope.dashscope.models.fast"));
        assertEquals("${DASHSCOPE_STRONG_MODEL:qwen-flash}",
                properties.getProperty("agentscope.dashscope.models.strong"));
        assertEquals("${DASHSCOPE_STABLE_MODEL:qwen-flash}",
                properties.getProperty("agentscope.dashscope.models.stable"));
    }
}
