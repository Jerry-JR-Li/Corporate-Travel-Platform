package com.gogo.travel.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherMcpConfigTest {

    @Test
    void disabledWeatherDoesNotCreateOrConnectMcpClient() {
        new ApplicationContextRunner()
                .withUserConfiguration(WeatherMcpConfig.class)
                .withPropertyValues("app.weather-mcp.enabled=false",
                        "app.weather-mcp.endpoint=http://unused.invalid")
                .run(context -> assertThat(context).doesNotHaveBean("weatherMcpClient"));
    }
}
