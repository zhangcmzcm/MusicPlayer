package org.example.music.playback.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.music.playback.config.PlaybackProgressProperties;
import org.example.music.playback.exception.PlaybackProgressRateLimitedException;
import org.example.music.playback.exception.PlaybackProgressRateLimitUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers

class PlaybackProgressRateLimiterIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    private static final String KEY = "music:playback-progress:user:42";
    private static final String OTHER_KEY = "music:playback-progress:user:43";
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static PlaybackProgressRateLimiter limiter;

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        limiter = new PlaybackProgressRateLimiter(redis, new PlaybackProgressProperties(120));
    }

    @AfterAll
    static void close() {
        if (connection != null) {
            connection.destroy();
        }
    }

    @BeforeEach
    void reset() {
        redis.delete(List.of(KEY, OTHER_KEY));
    }

    @Test
    void should_admit_exactly_120_concurrent_attempts() throws Exception {
        var executor = Executors.newFixedThreadPool(16);
        var start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 140; index++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        limiter.consumeOrThrow(42);
                        return true;
                    } catch (PlaybackProgressRateLimitedException exception) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(120, admitted);
            assertEquals("120", redis.opsForValue().get(KEY));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void should_keep_saturated_count_and_window_expiry() {
        limiter.consumeOrThrow(42);
        long expiry = expiry();
        for (int index = 1; index < 120; index++) {
            limiter.consumeOrThrow(42);
        }
        var failure = assertThrows(PlaybackProgressRateLimitedException.class, () -> limiter.consumeOrThrow(42));
        assertTrue(failure.getRetryAfterSeconds() >= 1 && failure.getRetryAfterSeconds() <= 60);
        assertEquals("120", redis.opsForValue().get(KEY));
        assertEquals(expiry, expiry());
        limiter.consumeOrThrow(43);
        assertEquals("1", redis.opsForValue().get(OTHER_KEY));
    }

    @Test
    void should_reopen_naturally_expired_window_and_preserve_shorter_ttl() {
        redis.opsForValue().set(KEY, "1", Duration.ofSeconds(30));
        long expiry = expiry();
        limiter.consumeOrThrow(42);
        assertEquals("2", redis.opsForValue().get(KEY));
        assertEquals(expiry, expiry());
        redis.expire(KEY, Duration.ofMillis(25));
        await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(KEY)));
        limiter.consumeOrThrow(42);
        assertEquals("1", redis.opsForValue().get(KEY));
        assertTrue(redis.getExpire(KEY, TimeUnit.MILLISECONDS) > 59000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "01", "+1", "1.0", "1e2", "bad", "2147483648", "99999999999"})
    void should_reject_invalid_counter_without_changing_state(String count) {
        redis.opsForValue().set(KEY, count, Duration.ofSeconds(60));
        long expiry = expiry();
        assertThrows(PlaybackProgressRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        assertEquals(count, redis.opsForValue().get(KEY));
        assertEquals(expiry, expiry());
    }

    @Test
    void should_reject_invalid_type_permanent_key_and_overlong_ttl() {
        redis.opsForHash().put(KEY, "count", "1");
        assertThrows(PlaybackProgressRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        redis.delete(KEY);
        redis.opsForValue().set(KEY, "1");
        assertThrows(PlaybackProgressRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        redis.expire(KEY, Duration.ofSeconds(120));
        assertThrows(PlaybackProgressRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        assertEquals("1", redis.opsForValue().get(KEY));
    }

    @Test
    void should_accept_existing_count_above_lowered_quota_and_integer_maximum() {
        redis.opsForValue().set(KEY, "40", Duration.ofSeconds(60));
        long expiry = expiry();
        var reduced = new PlaybackProgressRateLimiter(redis, new PlaybackProgressProperties(30));
        assertThrows(PlaybackProgressRateLimitedException.class, () -> reduced.consumeOrThrow(42));
        assertEquals("40", redis.opsForValue().get(KEY));
        assertEquals(expiry, expiry());
        redis.opsForValue().set(KEY, "2147483646", Duration.ofSeconds(60));
        var maximum = new PlaybackProgressRateLimiter(redis, new PlaybackProgressProperties(Integer.MAX_VALUE));
        maximum.consumeOrThrow(42);
        assertEquals("2147483647", redis.opsForValue().get(KEY));
        assertThrows(PlaybackProgressRateLimitedException.class, () -> maximum.consumeOrThrow(42));
    }

    @Test
    void should_handle_instantaneous_zero_and_missing_ttl_using_same_production_script() {
        redis.opsForValue().set(KEY, "120", Duration.ofSeconds(60));
        long expiry = expiry();
        assertEquals(List.of(1L, 1L), executeWithTtl(0));
        assertEquals("120", redis.opsForValue().get(KEY));
        assertEquals(expiry, expiry());
        assertEquals(List.of(0L, 0L), executeWithTtl(-2));
        assertEquals("1", redis.opsForValue().get(KEY));
        assertTrue(redis.getExpire(KEY, TimeUnit.MILLISECONDS) > 59000);
    }

    private List<?> executeWithTtl(long ttl) {
        String wrapper = "local original = redis\nlocal redis = { call = function(command, ...)\n"
                + "if command == 'PTTL' then return " + ttl + " end\n"
                + "return original.call(command, ...)\nend }\n";
        return redis.execute(new DefaultRedisScript<>(wrapper + PlaybackProgressRateLimiter.CONSUME.getScriptAsString(), List.class),
                List.of(KEY), "120");
    }

    private long expiry() {
        return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])", Long.class), List.of(KEY));
    }
}
