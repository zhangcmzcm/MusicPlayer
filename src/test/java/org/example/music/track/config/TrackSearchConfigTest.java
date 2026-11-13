package org.example.music.track.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TrackSearchConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TrackSearchConfig.class);

    @Test
    void should_bind_defaults_and_explicit_budgets() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(new TrackSearchProperties(60, 2000), context.getBean(TrackSearchProperties.class));
        });
        runner.withPropertyValues("app.track-search.rate-limit-per-minute=30", "app.track-search.query-timeout-ms=500")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(new TrackSearchProperties(30, 500), context.getBean(TrackSearchProperties.class));
                });
    }

    @ParameterizedTest
    @CsvSource({"rate-limit-per-minute,0", "rate-limit-per-minute,-1", "rate-limit-per-minute,2147483648",
            "query-timeout-ms,0", "query-timeout-ms,-1", "query-timeout-ms,2147483648"})
    void should_reject_invalid_budgets_at_startup(String property, String value) {
        runner.withPropertyValues("app.track-search." + property + "=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
