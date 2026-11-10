package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.example.music.user.domain.PasswordResetIdentifiers;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@EnabledIfDockerAvailable
class PasswordResetCompletionQuotaIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static PasswordResetCompletionQuotaStore quota;
    static final String PREFIX = PasswordResetCompletionQuotaStore.PREFIX;

    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        quota = new PasswordResetCompletionQuotaStore(redis);
    }

    @AfterAll static void close() { connection.destroy(); }

    @BeforeEach void clean() {
        var keys = redis.keys("music:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    long expiry(String key) {
        return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])", Long.class), List.of(key));
    }

    @Test void should_keep_independent_fixed_windows_without_refund_or_extension() {
        String digest = PasswordResetIdentifiers.generate();
        for (int attempt = 0; attempt < 10; attempt++) {
            quota.admitIp(digest);
            assertTrue(quota.admitAccount(42));
        }
        long ipEnd = expiry(PREFIX + "ip:" + digest);
        long userEnd = expiry(PREFIX + "user:42");
        var failure = assertThrows(PasswordResetConfirmationRateLimitedException.class, () -> quota.admitIp(digest));
        assertTrue(failure.retryAfterSeconds() >= 1 && failure.retryAfterSeconds() <= 60);
        assertFalse(quota.admitAccount(42));
        assertEquals(ipEnd, expiry(PREFIX + "ip:" + digest));
        assertEquals(userEnd, expiry(PREFIX + "user:42"));
        assertEquals("10", redis.opsForHash().get(PREFIX + "user:42", "count"));
        assertTrue(redis.keys(PasswordResetConfirmationStore.PREFIX + "*").isEmpty());
        var old = new PasswordResetConfirmationStore(redis, null);
        old.admitIp(digest);
        assertTrue(old.admitAccount(42));
        assertEquals("1", redis.opsForHash().get(PasswordResetConfirmationStore.PREFIX + "user:42", "count"));
        assertEquals(userEnd, expiry(PREFIX + "user:42"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "extra", "zero", "ttl", "expiry"})
    void should_fail_closed_on_damaged_quota(String damage) {
        assertTrue(quota.admitAccount(42));
        String key = PREFIX + "user:42";
        switch (damage) {
            case "type" -> { redis.delete(key); redis.opsForValue().set(key, "bad"); }
            case "extra" -> redis.opsForHash().put(key, "extra", "bad");
            case "zero" -> redis.opsForHash().put(key, "count", "0");
            case "ttl" -> redis.persist(key);
            case "expiry" -> redis.opsForHash().put(key, "resetAtMs", "1e3");
        }
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> quota.admitAccount(42));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
    }

    @Test void should_restore_only_after_original_window_expires() throws Exception {
        long now = redis.execute(new DefaultRedisScript<>(
                "local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)", Long.class), List.of());
        String key = PREFIX + "user:42";
        long ending = now + 300;
        redis.opsForHash().putAll(key, java.util.Map.of("count", "10", "resetAtMs", Long.toString(ending)));
        redis.expireAt(key, Instant.ofEpochMilli(ending));
        assertFalse(quota.admitAccount(42));
        assertEquals(ending, expiry(key));
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var elapsed = new CountDownLatch(1);
            scheduler.schedule(elapsed::countDown, 450, TimeUnit.MILLISECONDS);
            assertTrue(elapsed.await(2, TimeUnit.SECONDS));
        }
        assertTrue(quota.admitAccount(42));
        assertEquals("1", redis.opsForHash().get(key, "count"));
    }
}
