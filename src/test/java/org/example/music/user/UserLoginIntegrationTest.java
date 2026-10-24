package org.example.music.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.example.music.common.util.SmtpEmailVerificationCodeSender;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.service.EmailSendCodeService;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginRequest;
import org.example.music.user.dto.LoginResponse;
import org.example.music.user.dto.LoginStatus;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.exception.LoginRateLimitedException;
import org.example.music.user.exception.LoginTicketInvalidException;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.service.UserLoginService;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
class UserLoginIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
            .withExposedPorts(6379);

    @Autowired
    private UserLoginService userLoginService;

    @Autowired
    private EmailSendCodeService emailSendCodeService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private JwtDecoder jwtDecoder;

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
    void resetDatabase() {
        userRepository.deleteAll();
        deleteKeys("music:auth:*");
        deleteKeys("music:verification:code:*");
    }

    @Test
    void should_login_by_username_and_issue_jwt() {
        saveUser("alice", "alice@example.com", UserRole.USER);

        LoginResponse response = userLoginService.login(new LoginRequest(
                LoginType.USERNAME_PASSWORD,
                "alice",
                "correct-password",
                null,
                null,
                null,
                null
        ), "127.0.0.1");

        assertEquals(LoginStatus.AUTHENTICATED, response.status());
        Jwt jwt = jwtDecoder.decode(response.authentication().accessToken());
        assertEquals(String.valueOf(userRepository.findByUsername("alice").orElseThrow().getId()), jwt.getSubject());
        assertEquals("USER", jwt.getClaims().get("role"));
        assertTrue(response.authentication().expiresAt().isAfter(java.time.Instant.now()));
    }

    @Test
    void should_require_role_selection_and_issue_selected_admin_token() {
        UserEntity admin = saveUser("admin", "admin@example.com", UserRole.ADMIN);

        LoginResponse selection = userLoginService.login(new LoginRequest(
                LoginType.EMAIL_PASSWORD,
                "admin@example.com",
                "correct-password",
                null,
                null,
                null,
                null
        ), "127.0.0.1");

        assertEquals(LoginStatus.ROLE_SELECTION_REQUIRED, selection.status());
        assertNotNull(selection.roleSelection().loginTicket());

        LoginResponse authenticated = userLoginService.login(new LoginRequest(
                LoginType.ROLE_SELECTION,
                null,
                null,
                null,
                null,
                selection.roleSelection().loginTicket(),
                UserRole.ADMIN
        ), "127.0.0.1");

        Jwt jwt = jwtDecoder.decode(authenticated.authentication().accessToken());
        assertEquals(String.valueOf(admin.getId()), jwt.getSubject());
        assertEquals("ADMIN", jwt.getClaims().get("role"));
        assertEquals("EMAIL_PASSWORD", jwt.getClaims().get("loginType"));
    }

    @Test
    void should_login_by_email_code_and_consume_code_once() {
        saveUser("alice", "alice@example.com", UserRole.USER);
        emailSendCodeService.send(new EmailSendCodeRequest(
                "alice@example.com",
                VerificationCodePurpose.LOGIN
        ), "127.0.0.1");
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailVerificationCodeSender).send(
                org.mockito.ArgumentMatchers.eq("alice@example.com"),
                org.mockito.ArgumentMatchers.eq(VerificationCodePurpose.LOGIN),
                codeCaptor.capture(),
                org.mockito.ArgumentMatchers.any(java.time.Instant.class)
        );

        LoginResponse response = userLoginService.login(new LoginRequest(
                LoginType.EMAIL_CODE,
                null,
                null,
                "alice@example.com",
                codeCaptor.getValue(),
                null,
                null
        ), "127.0.0.1");

        assertEquals(LoginStatus.AUTHENTICATED, response.status());
        assertThrows(InvalidCredentialsException.class, () -> userLoginService.login(new LoginRequest(
                LoginType.EMAIL_CODE,
                null,
                null,
                "alice@example.com",
                codeCaptor.getValue(),
                null,
                null
        ), "127.0.0.1"));
    }

    @Test
    void should_reject_replayed_role_selection_ticket() {
        saveUser("admin", "admin@example.com", UserRole.ADMIN);
        LoginResponse selection = userLoginService.login(new LoginRequest(
                LoginType.USERNAME_PASSWORD,
                "admin",
                "correct-password",
                null,
                null,
                null,
                null
        ), "127.0.0.1");

        userLoginService.login(new LoginRequest(
                LoginType.ROLE_SELECTION,
                null,
                null,
                null,
                null,
                selection.roleSelection().loginTicket(),
                UserRole.USER
        ), "127.0.0.1");

        assertThrows(LoginTicketInvalidException.class, () -> userLoginService.login(new LoginRequest(
                LoginType.ROLE_SELECTION,
                null,
                null,
                null,
                null,
                selection.roleSelection().loginTicket(),
                UserRole.ADMIN
        ), "127.0.0.1"));
    }

    @Test
    void should_rate_limit_repeated_failed_logins() {
        saveUser("alice", "alice@example.com", UserRole.USER);
        LoginRequest request = new LoginRequest(
                LoginType.USERNAME_PASSWORD,
                "alice",
                "wrong-password",
                null,
                null,
                null,
                null
        );

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(InvalidCredentialsException.class, () -> userLoginService.login(request, "127.0.0.1"));
        }

        assertThrows(LoginRateLimitedException.class, () -> userLoginService.login(request, "127.0.0.1"));
    }

    private UserEntity saveUser(String username, String email, UserRole role) {
        return userRepository.save(new UserEntity(
                username,
                email,
                passwordHasher.hash("correct-password"),
                role
        ));
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}