package org.example.music.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.example.music.user.domain.UserEntity;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
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
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class UserRegistrationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

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
        Set<String> authKeys = redisTemplate.keys("music:auth:*");
        if (authKeys != null && !authKeys.isEmpty()) {
            redisTemplate.delete(authKeys);
        }
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

    @ParameterizedTest
    @ValueSource(ints = {8, 72})
    void should_register_boundary_password_through_http(int length) throws Exception {
        String password = "a".repeat(length);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("username", "boundary_user", "password", password))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userRole").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordToken").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
        UserEntity user = userRepository.findByUsername("boundary_user").orElseThrow();
        assertEquals(1, userRepository.count());
        assertEquals(UserRole.USER, user.getUserRole());
        assertNotEquals(password, user.getPasswordHash());
        assertTrue(passwordHasher.matches(password, user.getPasswordHash()));
    }

    @Test
    void should_preserve_password_spaces_in_stored_hash() throws Exception {
        String password = " abcdefgh ";
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("username", "space_user", "password", password))))
                .andExpect(status().isCreated());
        String hash = userRepository.findByUsername("space_user").orElseThrow().getPasswordHash();
        assertTrue(passwordHasher.matches(password, hash));
        assertFalse(passwordHasher.matches(password.trim(), hash));
    }

    @ParameterizedTest
    @ValueSource(strings = {"too_long", "non_ascii"})
    void should_reject_invalid_password_without_touching_account_or_code(String scenario) throws Exception {
        String password = scenario.equals("too_long") ? "a".repeat(73) : "汉".repeat(24);
        seedCode("alice@example.com", "123456");
        String key = KEY_PREFIX + "alice@example.com";
        Map<Object, Object> before = redisTemplate.opsForHash().entries(key);
        long ttlBefore = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("username", "invalid_user", "password", password,
                                "email", "alice@example.com", "code", "123456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:validation_failed"));
        assertEquals(0, userRepository.count());
        assertEquals(before, redisTemplate.opsForHash().entries(key));
        long ttlAfter = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertTrue(ttlAfter > 0 && ttlAfter <= ttlBefore);
    }

    @Test
    void should_keep_existing_non_ascii_password_login_compatible() throws Exception {
        String password = "historical-密码-123";
        userRepository.saveAndFlush(UserEntity.create("legacy_user", null, passwordHasher.hash(password)));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("loginType", "USERNAME_PASSWORD",
                                "account", "legacy_user", "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.authentication.accessToken").isString());
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
