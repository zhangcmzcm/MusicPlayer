package org.example.music.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Set;
import org.example.music.common.util.SmtpEmailVerificationCodeSender;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.dto.EmailSendCodeResponse;
import org.example.music.email.exception.SendCodeRateLimitedException;
import org.example.music.email.service.EmailSendCodeService;
import org.example.music.email.service.EmailSendRateLimitScope;
import org.example.music.security.PasswordHasher;
import org.example.music.verification.domain.EmailVerificationCodeSnapshot;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.repository.EmailVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class EmailSendCodeIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
            .withExposedPorts(6379);

    @Autowired
    private EmailSendCodeService emailSendCodeService;

    @Autowired
    private EmailVerificationCodeStore emailVerificationCodeStore;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private SmtpEmailVerificationCodeSender emailVerificationCodeSender;

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
    void resetRedis() {
        deleteKeys("music:verification:code:*");
        deleteKeys("music:email:send-code:*");
    }

    @Test
    void should_store_hashed_code_with_ttl_and_send_email() {
        EmailSendCodeResponse response = emailSendCodeService.send(request("alice@example.com"), "198.51.100.10");

        assertEquals(VerificationCodePurpose.REGISTER, response.purpose());
        EmailVerificationCodeSnapshot snapshot = code("alice@example.com");
        assertNotNull(snapshot.version());
        assertEquals(0, snapshot.attempts());
        long ttl = redisTemplate.getExpire(
                "music:verification:code:REGISTER:alice@example.com",
                java.util.concurrent.TimeUnit.SECONDS
        );
        assertTrue(ttl > 0 && ttl <= 300);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationCodeSender).send(
                eq("alice@example.com"),
                eq(VerificationCodePurpose.REGISTER),
                codeCaptor.capture(),
                eq(response.expiresAt())
        );
        assertTrue(passwordHasher.matches(codeCaptor.getValue(), snapshot.codeHash()));
        assertNotEquals(codeCaptor.getValue(), snapshot.codeHash());
    }

    @Test
    void should_enforce_email_cooldown() {
        emailSendCodeService.send(request("alice@example.com"), "198.51.100.10");

        SendCodeRateLimitedException exception = assertThrows(
                SendCodeRateLimitedException.class,
                () -> emailSendCodeService.send(request("alice@example.com"), "198.51.100.11")
        );

        assertEquals(EmailSendRateLimitScope.EMAIL_COOLDOWN, exception.getScope());
        assertTrue(exception.getRetryAfterSeconds() > 0);
    }

    @Test
    void should_enforce_ip_minute_limit() {
        for (int index = 1; index <= 5; index++) {
            emailSendCodeService.send(request("user" + index + "@example.com"), "198.51.100.10");
        }

        SendCodeRateLimitedException exception = assertThrows(
                SendCodeRateLimitedException.class,
                () -> emailSendCodeService.send(request("user6@example.com"), "198.51.100.10")
        );

        assertEquals(EmailSendRateLimitScope.IP_RATE_LIMIT, exception.getScope());
    }

    @Test
    void should_enforce_email_daily_limit() {
        for (int index = 1; index <= 10; index++) {
            deleteKeys("music:email:send-code:cooldown:*");
            emailSendCodeService.send(request("alice@example.com"), "198.51.100." + index);
        }
        deleteKeys("music:email:send-code:cooldown:*");

        SendCodeRateLimitedException exception = assertThrows(
                SendCodeRateLimitedException.class,
                () -> emailSendCodeService.send(request("alice@example.com"), "198.51.100.11")
        );

        assertEquals(EmailSendRateLimitScope.EMAIL_DAILY_LIMIT, exception.getScope());
    }

    @Test
    void should_not_remove_new_code_version_when_old_version_cleanup_runs() {
        emailSendCodeService.send(request("alice@example.com"), "198.51.100.10");
        String oldVersion = code("alice@example.com").version();
        deleteKeys("music:email:send-code:cooldown:*");
        emailSendCodeService.send(request("alice@example.com"), "198.51.100.11");
        String newVersion = code("alice@example.com").version();

        assertNotEquals(oldVersion, newVersion);
        assertEquals(false, emailVerificationCodeStore.removeIfVersion(
                "alice@example.com",
                VerificationCodePurpose.REGISTER,
                oldVersion
        ));
        assertEquals(newVersion, code("alice@example.com").version());
    }

    private EmailSendCodeRequest request(String email) {
        return new EmailSendCodeRequest(email, VerificationCodePurpose.REGISTER);
    }

    private EmailVerificationCodeSnapshot code(String email) {
        return emailVerificationCodeStore
                .find(email, VerificationCodePurpose.REGISTER)
                .orElseThrow();
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}