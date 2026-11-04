package org.example.music.user.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.example.music.user.domain.EmailChangeBinding;
import org.example.music.user.domain.EmailChangeReservation;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class EmailChangeRequestStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final EmailChangeRequestStore store = new EmailChangeRequestStore(redis);
    private final EmailChangeBinding binding = new EmailChangeBinding(42, "old@example.com", "alice@example.com", 7);
    private final String digest = "A".repeat(43);

    private void reply(Object value) {
        doReturn(value).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    static Stream<List<?>> invalidReplies() {
        return Stream.of(null, List.of(), List.of(0L), List.of(0L, 1L, 300001L, 60002L),
                List.of("0", 1L, 300001L, 60001L), List.of(0L, -1L, 299999L, 59999L),
                List.of(1L, 0L, 0L, 0L), List.of(1L, 86401L, 0L, 0L), List.of(2L, 0L, 0L, 0L),
                List.of(9L, 0L, 0L, 0L));
    }

    @ParameterizedTest @MethodSource("invalidReplies")
    void should_fail_closed_on_invalid_reservation_reply(List<?> result) {
        reply(result);
        assertThrows(EmailChangeRequestUnavailableException.class, () -> store.reserve(binding, digest, digest));
    }

    @Test
    void should_preserve_reservation_time_and_positive_retry_after() {
        reply(List.of(0L, 1000L, 301000L, 61000L));
        var result = store.reserve(binding, digest, digest);
        assertEquals(Instant.ofEpochMilli(1000), result.reservedAt());
        assertEquals(Instant.ofEpochMilli(301000), result.expiresAt());
        reply(List.of(1L, 900L, 0L, 0L));
        assertEquals(900, assertThrows(EmailChangeRateLimitedException.class,
                () -> store.reserve(binding, digest, digest)).getRetryAfterSeconds());
    }

    @Test
    void should_require_explicit_success_for_state_operations() {
        var reservation = new EmailChangeReservation(digest, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(300), Instant.EPOCH.plusSeconds(60));
        reply(List.of(0L));
        assertDoesNotThrow(() -> store.prepare(binding, reservation, "hash"));
        assertDoesNotThrow(() -> store.activate(binding, reservation));
        assertDoesNotThrow(() -> store.cancel(binding, digest));
        reply(List.of(1L));
        assertThrows(EmailChangeRequestConflictException.class, () -> store.activate(binding, reservation));
        assertThrows(EmailChangeRequestConflictException.class, () -> store.prepare(binding, reservation, "hash"));
        assertThrows(EmailChangeRequestUnavailableException.class, () -> store.cancel(binding, digest));
        reply(null);
        assertThrows(EmailChangeRequestUnavailableException.class, () -> store.activate(binding, reservation));
    }

    @Test
    void should_translate_redis_failure_without_retry_or_fallback() {
        doThrow(new DataAccessResourceFailureException("private data"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThrows(EmailChangeRequestUnavailableException.class, () -> store.reserve(binding, digest, digest));
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
