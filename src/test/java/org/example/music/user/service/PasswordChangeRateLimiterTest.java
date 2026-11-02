package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.Stream;
import org.example.music.user.exception.PasswordChangeRateLimitedException;
import org.example.music.user.exception.PasswordChangeRateLimitUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class PasswordChangeRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PasswordChangeRateLimiter limiter = new PasswordChangeRateLimiter(redis);

    static Stream<List<?>> invalidResults() {
        return Stream.of(null, List.of(), List.of(0L), List.of(0L, 0L, 0L), List.of("0", 0L),
                List.of(3L, 0L), List.of(0L, 1L), List.of(1L, 0L), List.of(1L, 901L), List.of(2L, 0L));
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void should_fail_closed_on_unknown_or_failed_script_result(List<?> result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList());
        assertThrows(PasswordChangeRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
    }

    @Test
    void should_allow_only_explicit_permission_and_use_actor_key() {
        doReturn(List.of(0L, 0L)).when(redis).execute(any(RedisScript.class),
                eq(List.of("music:auth:password-change:user:42")));
        assertDoesNotThrow(() -> limiter.consumeOrThrow(42));
        verifyNoMoreInteractionsAfterScript();
    }

    private void verifyNoMoreInteractionsAfterScript() {
        verify(redis).execute(any(RedisScript.class), eq(List.of("music:auth:password-change:user:42")));
        verifyNoMoreInteractions(redis);
    }

    @Test
    void should_report_positive_retry_after() {
        doReturn(List.of(1L, 900L)).when(redis).execute(any(RedisScript.class), anyList());
        assertEquals(900, assertThrows(PasswordChangeRateLimitedException.class,
                () -> limiter.consumeOrThrow(42)).getRetryAfterSeconds());
    }

    @Test
    void should_translate_redis_failure_without_retrying() {
        doThrow(new DataAccessResourceFailureException("private backend"))
                .when(redis).execute(any(RedisScript.class), anyList());
        assertThrows(PasswordChangeRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        verifyNoMoreInteractionsAfterScript();
    }
}
