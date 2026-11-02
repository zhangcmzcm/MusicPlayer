package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.example.music.user.exception.PasswordChangeRateLimitedException;
import org.example.music.user.exception.PasswordChangeRateLimitUnavailableException;
import org.example.music.user.service.PasswordChangeRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class PasswordChangeIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
            .withExposedPorts(6379);
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordChangeRateLimiter limiter;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "default");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> "0");
    }

    @BeforeEach
    void resetData() {
        Set<String> keys = redis.keys("music:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private String rateKey(int id) {
        return "music:auth:password-change:user:" + id;
    }

    @Test
    void should_allow_five_attempts_then_limit_without_extending_window() {
        for (int attempt = 0; attempt < 5; attempt++) limiter.consumeOrThrow(42);
        long before = redis.getExpire(rateKey(42), TimeUnit.MILLISECONDS);
        var failure = assertThrows(PasswordChangeRateLimitedException.class, () -> limiter.consumeOrThrow(42));
        long after = redis.getExpire(rateKey(42), TimeUnit.MILLISECONDS);
        assertTrue(before > 0 && before <= 900000 && after <= before && after > 0);
        assertTrue(failure.getRetryAfterSeconds() >= (after + 999) / 1000);
        assertTrue(failure.getRetryAfterSeconds() <= (before + 999) / 1000);
        assertEquals("5", redis.opsForValue().get(rateKey(42)));
        assertDoesNotThrow(() -> limiter.consumeOrThrow(43));
        redis.expire(rateKey(42), Duration.ZERO);
        assertDoesNotThrow(() -> limiter.consumeOrThrow(42));
        assertEquals("1", redis.opsForValue().get(rateKey(42)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "6", "01", "١", "unknown", "99999999999999999999"})
    void should_fail_closed_on_corrupt_counter(String count) {
        redis.opsForValue().set(rateKey(42), count, Duration.ofSeconds(900));
        assertThrows(PasswordChangeRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        assertEquals(count, redis.opsForValue().get(rateKey(42)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hash", "list", "no_ttl", "long_ttl"})
    void should_fail_closed_on_corrupt_type_or_ttl(String scenario) {
        switch (scenario) {
            case "hash" -> redis.opsForHash().put(rateKey(42), "count", "1");
            case "list" -> redis.opsForList().rightPush(rateKey(42), "1");
            case "no_ttl" -> redis.opsForValue().set(rateKey(42), "1");
            case "long_ttl" -> redis.opsForValue().set(rateKey(42), "1", Duration.ofSeconds(1800));
        }
        assertThrows(PasswordChangeRateLimitUnavailableException.class, () -> limiter.consumeOrThrow(42));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(rateKey(42))));
    }

    @Test
    void should_admit_at_most_five_concurrent_attempts() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        try {
            var futures = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    limiter.consumeOrThrow(42);
                    return true;
                } catch (PasswordChangeRateLimitedException expected) {
                    return false;
                }
            }));
            start.countDown();
            int admitted = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) admitted++;
            assertEquals(5, admitted);
            assertEquals("5", redis.opsForValue().get(rateKey(42)));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
