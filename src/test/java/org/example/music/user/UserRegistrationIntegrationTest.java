package org.example.music.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.example.music.verification.domain.VerificationCodeEntity;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.exception.EmailVerificationCodeException;
import org.example.music.verification.repository.VerificationCodeRepository;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@EnabledIfDockerAvailable
class UserRegistrationIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    @Autowired
    private EmailVerificationCodeService emailVerificationCodeService;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void resetDatabase() {
        createUsersTable();
        jdbcTemplate.update("DELETE FROM verification_codes");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void should_create_user_and_consume_code_when_registration_is_valid() {
        seedCode("alice@example.com", "123456", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));

        UserRegisterResponse response = userRegistrationService.register(new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "Alice@Example.COM",
                "123456"
        ));

        assertEquals("alice@example.com", response.email());
        assertEquals(UserRole.USER, response.userRole());
        assertEquals(1, userRepository.count());
        VerificationCodeEntity code = verificationCodeRepository.findAll().getFirst();
        assertNotNull(code.getUsedAt());
    }

    @Test
    void should_preserve_failed_attempt_when_code_is_wrong() {
        VerificationCodeEntity code = seedCode(
                "alice@example.com",
                "123456",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)
        );

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
        assertEquals(1, verificationCodeRepository.findById(code.getId()).orElseThrow().getAttempts());
        assertEquals(0, userRepository.count());
    }

    @Test
    void should_block_registration_after_five_invalid_attempts() {
        seedCode("alice@example.com", "123456", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));

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
        assertEquals(5, verificationCodeRepository.findAll().getFirst().getAttempts());
        assertEquals(0, userRepository.count());
    }

    @Test
    void should_reject_reused_code() {
        seedCode("alice@example.com", "123456", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        userRegistrationService.register(new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                "alice@example.com",
                "123456"
        ));
        jdbcTemplate.update("DELETE FROM users");

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
    void should_allow_only_one_success_for_concurrent_duplicate_registration() throws Exception {
        seedCode("alice@example.com", "123456", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
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
            assertTrue(verificationCodeRepository.findAll().getFirst().isUsed());
        } finally {
            executor.shutdownNow();
        }
    }

    private VerificationCodeEntity seedCode(String email, String code, LocalDateTime expiresAt) {
        VerificationCodeEntity verificationCode = new VerificationCodeEntity(
                email,
                VerificationCodePurpose.REGISTER,
                passwordHasher.hash(code),
                expiresAt,
                null
        );
        return verificationCodeRepository.save(verificationCode);
    }

    private void createUsersTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS `users` (
                  `id` int NOT NULL AUTO_INCREMENT COMMENT '用户ID',
                  `username` varchar(80) NOT NULL COMMENT '用户名',
                  `email` varchar(120) DEFAULT NULL COMMENT '邮箱',
                  `password_token` varchar(255) NOT NULL COMMENT '密码加密token',
                  `user_role` enum('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '用户最高权限角色',
                  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
                  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
                  `last_login_at` datetime DEFAULT NULL COMMENT '上次登录时间',
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `ix_users_username` (`username`),
                  UNIQUE KEY `ix_users_email` (`email`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
    }
}