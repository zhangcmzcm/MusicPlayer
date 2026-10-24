package org.example.music.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.service.UserRegistrationService;
import org.example.music.verification.domain.EmailVerificationCodeSnapshot;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.repository.EmailVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class UserRegistrationIntegrationTest {

    private static final String KEY_PREFIX = "music:verification:code:REGISTER:";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4"))
            .withExposedPorts(6379);

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationCodeStore emailVerificationCodeStore;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private StringRedisTemplate redisTemplate;
    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> "0");
        registry.add("spring.data.redis.username", () -> "default");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.timeout", () -> "2s");
    }

    @BeforeEach
    void resetDatabase() {
        userRepository.deleteAll();
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void should_create_user_and_consume_code_when_registration_is_valid() {
        seedCode("alice@example.com", "123456");

        UserRegisterResponse response = userRegistrationService.register(new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "Alice@Example.COM",
                "123456"
        ));

        assertEquals("alice@example.com", response.email());
        assertEquals(UserRole.USER, response.userRole());
        assertEquals(1, userRepository.count());
        EmailVerificationCodeSnapshot code = code("alice@example.com");
        assertNotNull(code.usedAt());
        assertTrue(redisTemplate.getExpire(KEY_PREFIX + "alice@example.com", TimeUnit.SECONDS) > 0);
    }

    @Test
    void should_preserve_failed_attempt_when_code_is_wrong() {
        seedCode("alice@example.com", "123456");

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> userRegistrationService.register(new UserRegisterRequest(
                        "alice_2026",
                        "correct-password",
                        "alice@example.com",
                        "654321"
                ))
        );

        assertEquals(EmailVerificationCodeException.Failure.INVALID, exception.getFailure());
        assertEquals(1, code("alice@example.com").attempts());
        assertEquals(0, userRepository.count());
    }

    @Test
    void should_block_registration_after_five_invalid_attempts() {
        seedCode("alice@example.com", "123456");

        EmailVerificationCodeException exception = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            String username = "alice_2026_" + attempt;
            exception = assertThrows(
                    EmailVerificationCodeException.class,
                    () -> userRegistrationService.register(new UserRegisterRequest(
                            username,
                            "correct-password",
                            "alice@example.com",
                            "654321"
                    ))
            );
        }

        assertEquals(EmailVerificationCodeException.Failure.ATTEMPTS_EXCEEDED, exception.getFailure());
        assertEquals(5, code("alice@example.com").attempts());
        assertEquals(0, userRepository.count());
    }

    @Test
    void should_reject_reused_code() {
        seedCode("alice@example.com", "123456");
        userRegistrationService.register(new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "alice@example.com",
                "123456"
        ));
        userRepository.deleteAll();

        EmailVerificationCodeException exception = assertThrows(
                EmailVerificationCodeException.class,
                () -> userRegistrationService.register(new UserRegisterRequest(
                        "another_user",
                        "correct-password",
                        "alice@example.com",
                        "123456"
                ))
        );

        assertEquals(EmailVerificationCodeException.Failure.ALREADY_USED, exception.getFailure());
    }

    @Test
    void should_reset_attempts_when_new_code_replaces_old_code() {
        String firstVersion = seedCode("alice@example.com", "123456");
        assertThrows(
                EmailVerificationCodeException.class,
                () -> userRegistrationService.register(new UserRegisterRequest(
                        "alice_2026",
                        "correct-password",
                        "alice@example.com",
                        "654321"
                ))
        );

        String secondVersion = seedCode("alice@example.com", "654321");
        EmailVerificationCodeSnapshot code = code("alice@example.com");

        assertNotEquals(firstVersion, secondVersion);
        assertEquals(secondVersion, code.version());
        assertEquals(0, code.attempts());
        assertNull(code.usedAt());
    }

    @Test
    void should_allow_only_one_success_for_concurrent_duplicate_registration() throws Exception {
        seedCode("alice@example.com", "123456");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                attempts.add(() -> {
                    start.await();
                    try {
                        userRegistrationService.register(new UserRegisterRequest(
                                "alice_2026",
                                "correct-password",
                                "alice@example.com",
                                "123456"
                        ));
                        return true;
                    } catch (RuntimeException exception) {
                        return false;
                    }
                });
            }

            List<Future<Boolean>> futures = attempts.stream().map(executor::submit).toList();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }

            assertEquals(1, successes);
            assertEquals(1, userRepository.count());
            assertNotNull(code("alice@example.com").usedAt());
        } finally {
            executor.shutdownNow();
        }
    }

    private String seedCode(String email, String code) {
        String key = KEY_PREFIX + email;
        String version = UUID.randomUUID().toString();
        Map<String, String> values = new HashMap<>();
        values.put("codeHash", passwordHasher.hash(code));
        values.put("version", version);
        values.put("attempts", "0");
        values.put("usedAt", "");
        redisTemplate.opsForHash().putAll(key, values);
        redisTemplate.expire(key, Duration.ofSeconds(300));
        return version;
    }

    private EmailVerificationCodeSnapshot code(String email) {
        return emailVerificationCodeStore
                .find(email, VerificationCodePurpose.REGISTER)
                .orElseThrow();
    }

}