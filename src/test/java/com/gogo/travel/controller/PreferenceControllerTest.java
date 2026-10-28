package com.gogo.travel.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.gogo.travel.agent.memory.*;
import io.agentscope.core.memory.LongTermMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreferenceControllerTest {
    @Test void failedAndDisabledStorageMustNeverReportSaved() {
        var factory = mock(TravelPreferenceLongTermMemoryFactory.class);
        var memory = mock(LongTermMemory.class);
        var controller = new PreferenceController();
        ReflectionTestUtils.setField(controller, "memoryFactory", factory);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsString).thenReturn("alice");
            when(factory.create("alice")).thenReturn(memory);
            when(memory.record(anyList())).thenReturn(Mono.error(new IllegalStateException("offline")));
            when(memory.retrieve(any())).thenReturn(Mono.error(new IllegalStateException("offline")));
            var response = controller.saveUserPreferences(Map.of("hotel_star", "comfortable"));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).containsEntry("saved", false);
            assertThat(controller.getUserPreferences().getStatusCode().value()).isEqualTo(503);
            when(factory.create("alice")).thenReturn(null);
            assertThat(controller.saveUserPreferences(Map.of()).getStatusCode().value()).isEqualTo(503);
            assertThat(controller.getUserPreferences().getStatusCode().value()).isEqualTo(503);
        }
    }
}
