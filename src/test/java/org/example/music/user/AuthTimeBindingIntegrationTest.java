package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.example.music.common.util.SmtpEmailVerificationCodeSender;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.service.EmailSendCodeService;
import org.example.music.security.AuthenticatedSessionContext;
import org.example.music.security.JwtTokenService;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.service.LoginTicketService;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class AuthTimeBindingIntegrationTest {
    private static final String SECRET = "test-secret-that-is-long-enough-1234567890";
    @Container private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");
    @Container private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
            .withExposedPorts(6379);
    @Autowired private MockMvc mvc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtDecoder decoder;
    @Autowired private EmailSendCodeService sendCodes;
    @MockitoBean(name = "authenticationClock") private Clock clock;
    @MockitoBean private SmtpEmailVerificationCodeSender mail;
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private PasswordHasher hasher;
    @MockitoSpyBean private EmailVerificationCodeService codes;
    @MockitoSpyBean private LoginTicketService tickets;
    @MockitoSpyBean private JwtTokenService tokens;
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final JsonMapper json = JsonMapper.builder().build();

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
        now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        users.deleteAll();
        Set<String> keys = redis.keys("music:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private UserEntity save(UserRole role) {
        return users.saveAndFlush(new UserEntity("alice", "alice@example.com", hasher.hash("correct-password"), role));
    }

    private MvcResult login(Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private Map<String, String> passwordBody(LoginType type) {
        return Map.of("loginType", type.name(), "account", type == LoginType.EMAIL_PASSWORD ? "alice@example.com" : "alice",
                "password", "correct-password");
    }

    private MvcResult selection(String ticket, UserRole role) throws Exception {
        return login(Map.of("loginType", "ROLE_SELECTION", "loginTicket", ticket, "loginRole", role.name()));
    }

    private String field(MvcResult response, String object, String name) throws Exception {
        return json.readTree(response.getResponse().getContentAsString()).path(object).path(name).asText();
    }

    private String ticketKey(String ticket) throws Exception {
        return "music:auth:login-ticket:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));
    }

    private String sendCode() {
        sendCodes.send(new EmailSendCodeRequest("alice@example.com", VerificationCodePurpose.LOGIN), "127.0.0.1");
        var capture = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("alice@example.com"), eq(VerificationCodePurpose.LOGIN), capture.capture(), any(Instant.class));
        return capture.getValue();
    }

    private void problem(MvcResult response, int status, String code) throws Exception {
        assertEquals(status, response.getResponse().getStatus(), response.getResponse().getContentAsString());
        var body = json.readTree(response.getResponse().getContentAsString());
        assertEquals(code, body.path("code").asText());
        assertEquals("urn:problem:music:" + code.toLowerCase(), body.path("type").asText());
    }

    private Object real(Object spy, InvocationOnMock invocation) throws Throwable {
        return mockingDetails(spy).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    @ParameterizedTest
    @CsvSource({"USERNAME_PASSWORD,USER,USER", "EMAIL_PASSWORD,USER,USER", "EMAIL_CODE,USER,USER",
            "USERNAME_PASSWORD,ADMIN,USER", "USERNAME_PASSWORD,ADMIN,ADMIN", "EMAIL_PASSWORD,ADMIN,USER",
            "EMAIL_PASSWORD,ADMIN,ADMIN", "EMAIL_CODE,ADMIN,USER", "EMAIL_CODE,ADMIN,ADMIN"})
    void should_preserve_verified_time_across_all_modes_and_role_selection(LoginType type, UserRole accountRole, UserRole role) throws Exception {
        var user = save(accountRole);
        Instant verified = now.get();
        MvcResult response = login(type == LoginType.EMAIL_CODE
                ? Map.of("loginType", "EMAIL_CODE", "email", "alice@example.com", "code", sendCode()) : passwordBody(type));
        assertEquals(200, response.getResponse().getStatus());
        if (accountRole == UserRole.ADMIN) {
            String ticket = field(response, "roleSelection", "loginTicket");
            var stored = redis.opsForHash().entries(ticketKey(ticket));
            assertEquals(Set.of("userId", "maximumRole", "loginType", "tokenVersion", "authTime"), stored.keySet());
            assertEquals(String.valueOf(verified.getEpochSecond()), stored.get("authTime"));
            assertTrue(redis.getExpire(ticketKey(ticket), TimeUnit.MILLISECONDS) > 0);
            now.set(verified.plusSeconds(600));
            response = selection(ticket, role);
        }
        assertEquals(200, response.getResponse().getStatus());
        String signed = field(response, "authentication", "accessToken");
        var payload = json.readTree(new String(Base64.getUrlDecoder().decode(signed.split("\\.")[1]), StandardCharsets.UTF_8));
        assertTrue(payload.path("auth_time").isIntegralNumber());
        assertEquals(verified.getEpochSecond(), payload.path("auth_time").asLong());
        assertEquals(now.get().getEpochSecond(), decoder.decode(signed).getIssuedAt().getEpochSecond());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + signed)).andExpect(status().isOk());
        verify(tokens, never()).issueLegacyWithoutAuthTime(any(), any(), any());
        assertEquals(0, users.findById(user.getId()).orElseThrow().getTokenVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"password_query_delay", "email_consume_delay", "signing_delay"})
    void should_not_refresh_proof_time_during_later_work(String window) throws Exception {
        save(UserRole.USER);
        Instant verified = now.get();
        if (window.equals("password_query_delay")) doAnswer(invocation -> {
            now.set(verified.plusSeconds(600));
            return real(users, invocation);
        }).when(users).findAuthorizationSnapshot(anyInt());
        if (window.equals("email_consume_delay")) doAnswer(invocation -> {
            now.set(verified.plusSeconds(600));
            return real(codes, invocation);
        }).when(codes).consume(any());
        if (window.equals("signing_delay")) doAnswer(invocation -> {
            now.set(verified.plusSeconds(600));
            return real(tokens, invocation);
        }).when(tokens).issue(any(), any(), any(), any());
        var result = login(window.equals("email_consume_delay")
                ? Map.of("loginType", "EMAIL_CODE", "email", "alice@example.com", "code", sendCode())
                : passwordBody(LoginType.USERNAME_PASSWORD));
        assertEquals(200, result.getResponse().getStatus());
        var signed = field(result, "authentication", "accessToken");
        assertEquals(verified.getEpochSecond(), ((Number) decoder.decode(signed).getClaim("auth_time")).longValue());
        assertEquals(verified.plusSeconds(600), decoder.decode(signed).getIssuedAt());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + signed)).andExpect(status().isOk());
    }

    private String rawToken(int id, String literal, String iat) throws Exception {
        String payload = "{\"iss\":\"music-api\",\"aud\":[\"music-client\"],\"sub\":\"" + id
                + "\",\"exp\":" + now.get().plusSeconds(86400).getEpochSecond()
                + ",\"role\":\"USER\",\"tokenVersion\":0"
                + (iat == null ? "" : ",\"iat\":" + iat)
                + (literal == null ? "" : ",\"auth_time\":" + literal) + "}";
        var signed = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(payload));
        signed.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return signed.serialize();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"0\"", "\"1793620800\"", "true", "[]", "{}", "1.0", "1e0", "1.5", "-1",
            "9223372036854775807", "9223372036854775808", "future", "after_iat", "missing_iat", "null_iat"})
    void should_reject_invalid_raw_signed_authentication_time(String literal) throws Exception {
        var user = save(UserRole.USER);
        String iat = String.valueOf(now.get().getEpochSecond());
        if (literal.equals("future")) { iat = String.valueOf(now.get().plusSeconds(1).getEpochSecond()); literal = iat; }
        if (literal.equals("after_iat")) { literal = iat; iat = String.valueOf(now.get().minusSeconds(1).getEpochSecond()); }
        if (literal.equals("missing_iat")) { literal = iat; iat = null; }
        if (literal.equals("null_iat")) { literal = iat; iat = "null"; }
        String token = rawToken(user.getId(), literal, iat);
        clearInvocations(users);
        problem(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andReturn(), 401, "INVALID_TOKEN");
        verify(users, never()).findProfileSnapshot(anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "zero", "now", "old"})
    void should_publish_only_verified_context_and_keep_legacy_ordinary_access(String kind) throws Exception {
        var user = save(UserRole.USER);
        String literal = switch (kind) {
            case "missing" -> null;
            case "zero" -> "0";
            case "old" -> String.valueOf(now.get().minusSeconds(900).getEpochSecond());
            default -> String.valueOf(now.get().getEpochSecond());
        };
        var observed = new AtomicReference<AuthenticatedSessionContext>();
        doAnswer(invocation -> {
            observed.set(AuthenticatedSessionContext.from(SecurityContextHolder.getContext().getAuthentication()).orElseThrow());
            return real(users, invocation);
        }).when(users).findProfileSnapshot(user.getId());
        String token = rawToken(user.getId(), literal, String.valueOf(now.get().getEpochSecond()));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)
                        .header("X-Auth-Time", "9999999999").param("auth_time", "9999999999"))
                .andExpect(status().isOk());
        assertNotNull(observed.get());
        assertEquals(0, observed.get().tokenVersion());
        assertEquals(literal == null ? null : Instant.ofEpochSecond(Long.parseLong(literal)), observed.get().authenticatedAt());
        jdbc.update("UPDATE users SET token_version = 1 WHERE id = ?", user.getId());
        problem(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andReturn(), 401, "TOKEN_REVOKED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"four_fields", "future", "unknown", "no_ttl", "wrong_type"})
    void should_reject_invalid_real_ticket_without_time_fallback(String kind) throws Exception {
        var user = save(UserRole.ADMIN);
        var result = login(passwordBody(LoginType.USERNAME_PASSWORD));
        String ticket = field(result, "roleSelection", "loginTicket");
        String key = ticketKey(ticket);
        switch (kind) {
            case "four_fields" -> redis.opsForHash().delete(key, "authTime");
            case "future" -> redis.opsForHash().put(key, "authTime", String.valueOf(now.get().plusSeconds(1).getEpochSecond()));
            case "unknown" -> redis.opsForHash().put(key, "other", "field");
            case "no_ttl" -> redis.persist(key);
            case "wrong_type" -> { redis.delete(key); redis.opsForValue().set(key, "bad"); }
        }
        problem(selection(ticket, UserRole.USER), 401, "LOGIN_TICKET_INVALID");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        verify(tokens, never()).issue(any(), any(), any(), any());
    }

    @Test
    void should_preserve_time_and_once_only_consumption_under_concurrency() throws Exception {
        save(UserRole.ADMIN);
        Instant verified = now.get();
        String ticket = field(login(passwordBody(LoginType.USERNAME_PASSWORD)), "roleSelection", "loginTicket");
        now.set(verified.plusSeconds(60));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> attempt = () -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return selection(ticket, UserRole.USER); };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(List.of(200, 401), responses.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
            for (var response : responses) {
                if (response.getResponse().getStatus() == 200) {
                    assertEquals(verified.getEpochSecond(), ((Number) decoder.decode(field(response, "authentication", "accessToken")).getClaim("auth_time")).longValue());
                } else problem(response, 401, "LOGIN_TICKET_INVALID");
            }
        } finally {
            start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
