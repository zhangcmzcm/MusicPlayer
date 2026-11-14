package org.example.music.playback.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlaybackProgressConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PlaybackProgressConfig.class);

    @Test
    void should_bind_defaults_and_explicit_quota() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(new PlaybackProgressProperties(120), context.getBean(PlaybackProgressProperties.class));
        });
        runner.withPropertyValues("app.playback-progress.rate-limit-per-minute=30").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(new PlaybackProgressProperties(30), context.getBean(PlaybackProgressProperties.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "invalid"})
    void should_reject_invalid_quota_at_startup(String value) {
        runner.withPropertyValues("app.playback-progress.rate-limit-per-minute=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
