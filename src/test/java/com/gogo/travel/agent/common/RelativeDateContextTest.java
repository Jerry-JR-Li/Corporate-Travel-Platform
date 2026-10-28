package com.gogo.travel.agent.common;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class RelativeDateContextTest {
    @Test void nextMondayAndFridayAreComputedAcrossMonthBoundary() {
        String context=PromptLoader.buildTimeContext(Clock.fixed(Instant.parse("2026-10-28T15:05:00Z"),ZoneOffset.UTC));
        assertThat(context).contains("2026-10-28（星期三）", "下星期一=2026-11-02", "下星期五=2026-11-06");
    }
    @Test void businessTimezoneAndYearBoundaryAreExplicit() {
        String context=PromptLoader.buildTimeContext(Clock.fixed(Instant.parse("2026-12-31T17:00:00Z"),ZoneOffset.UTC));
        assertThat(context).contains("2027-01-01（星期五）", "下星期一=2027-01-04", "下星期日=2027-01-10");
    }
}
