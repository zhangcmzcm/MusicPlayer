package org.example.music.playback.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.stream.Stream;
import org.example.music.playback.config.PlaybackProgressProperties;
import org.example.music.playback.exception.PlaybackProgressRateLimitedException;
import org.example.music.playback.exception.PlaybackProgressRateLimitUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class PlaybackProgressRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PlaybackProgressRateLimiter limiter = new PlaybackProgressRateLimiter(redis, new PlaybackProgressProperties(120));

    static Stream<List<?>> invalidResults() {
        return Stream.of(null, List.of(), List.of(0L), List.of(0L, 0L, 0L), List.of("0", 0L),
                List.of(0L, "0"), List.of(0L, 1L), List.of(1L, 0L), List.of(1L, 61L),
                List.of(1L, -1L), List.of(2L, 0L), List.of(3L, 0L));
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void should_reject_unknown_or_failed_script_result(List<?> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), eq("120"));
        assertThrows(PlaybackProgressRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
    }

    @Test
    void should_use_account_key_and_configured_quota() {
        doReturn(List.of(0L, 0L)).when(redis).execute(any(RedisScript.class), eq(List.of("music:playback-progress:user:42")), eq("120"));
        assertDoesNotThrow(() -> limiter.consumeOrThrow(42));
        verify(redis).execute(any(RedisScript.class), eq(List.of("music:playback-progress:user:42")), eq("120"));
        verifyNoMoreInteractions(redis);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 60})
    void should_report_positive_retry_after(long seconds) {
        doReturn(List.of(1L, seconds)).when(redis).execute(any(RedisScript.class), anyList(), eq("120"));
        assertEquals(seconds, assertThrows(PlaybackProgressRateLimitedException.class,
                () -> limiter.consumeOrThrow(42)).getRetryAfterSeconds());
    }

    @Test
    void should_translate_redis_failure_without_retry() {
        doThrow(new DataAccessResourceFailureException("private backend"))
                .when(redis).execute(any(RedisScript.class), anyList(), eq("120"));
        assertThrows(PlaybackProgressRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        verify(redis).execute(any(RedisScript.class), eq(List.of("music:playback-progress:user:42")), eq("120"));
        verifyNoMoreInteractions(redis);
    }
}
