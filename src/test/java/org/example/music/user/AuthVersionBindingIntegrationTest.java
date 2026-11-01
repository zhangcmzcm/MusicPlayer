package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.example.music.common.util.SmtpEmailVerificationCodeSender;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.service.EmailSendCodeService;
import org.example.music.security.JwtTokenService;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.example.music.user.service.LoginTicketService;
import org.example.music.user.service.UserAuthorizationStateService;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.example.music.verification.service.VerifiedEmailCode;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class AuthVersionBindingIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
            .withExposedPorts(6379);

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private UserRoleHistoryRepository history;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtDecoder decoder;
    @Autowired private EmailSendCodeService sendCodes;
    @MockitoSpyBean private PasswordHasher hasher;
    @MockitoSpyBean private LoginTicketService tickets;
    @MockitoSpyBean private JwtTokenService jwtTokens;
    @MockitoSpyBean private UserAuthorizationStateService states;
    @MockitoSpyBean private EmailVerificationCodeService codes;
    @MockitoSpyBean private StringRedisTemplate redis;
    @MockitoBean private SmtpEmailVerificationCodeSender mail;
    private final ObjectMapper json = new ObjectMapper();

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
        history.deleteAll();
        users.deleteAll();
        Set<String> keys = redis.keys("music:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "USERNAME_PASSWORD,USER,USER,0", "EMAIL_PASSWORD,USER,USER,7", "EMAIL_CODE,USER,USER,0",
            "USERNAME_PASSWORD,ADMIN,USER,7", "USERNAME_PASSWORD,ADMIN,ADMIN,0",
            "EMAIL_PASSWORD,ADMIN,USER,0", "EMAIL_PASSWORD,ADMIN,ADMIN,7",
            "EMAIL_CODE,ADMIN,USER,7", "EMAIL_CODE,ADMIN,ADMIN,0"
    })
    void should_preserve_all_login_modes_with_real_dependencies(
            LoginType type, UserRole accountRole, UserRole loginRole, long version
    ) throws Exception {
        UserEntity user = save(accountRole, version);
        String body = type == LoginType.EMAIL_CODE ? codeBody(sendCode()) : passwordBody(type);
        MvcResult result = login(body);
        assertEquals(200, result.getResponse().getStatus());
        if (accountRole == UserRole.ADMIN) {
            String ticket = tree(result).path("roleSelection").path("loginTicket").asText();
            var fields = redis.opsForHash().entries(ticketKey(ticket));
            assertEquals(Set.of("userId", "maximumRole", "loginType", "tokenVersion"), fields.keySet());
            assertEquals(String.valueOf(version), fields.get("tokenVersion"));
            Long ttl = redis.getExpire(ticketKey(ticket), TimeUnit.MILLISECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 300_000);
            result = login(selectionBody(ticket, loginRole));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(ticketKey(ticket))));
            problem(login(selectionBody(ticket, loginRole)), 401, "LOGIN_TICKET_INVALID");
        }
        assertEquals(200, result.getResponse().getStatus());
        String token = tree(result).path("authentication").path("accessToken").asText();
        var decoded = decoder.decode(token);
        assertEquals(version, ((Number) decoded.getClaim("tokenVersion")).longValue());
        assertEquals(loginRole.name(), decoded.getClaimAsString("role"));
        assertEquals(type.name(), decoded.getClaimAsString("loginType"));
        assertEquals(user.getId().toString(), decoded.getSubject());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
    }

    @ParameterizedTest
    @CsvSource({"USERNAME_PASSWORD,USER", "EMAIL_PASSWORD,USER", "USERNAME_PASSWORD,ADMIN", "EMAIL_PASSWORD,ADMIN"})
    void should_reject_version_change_after_real_password_verification(LoginType type, UserRole role) throws Exception {
        UserEntity user = save(role, 0);
        var verified = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        pauseAfterPasswordVerification(verified, resume);
        MvcResult result = during(() -> login(passwordBody(type)), verified, resume, () -> bump(user.getId()));
        problem(result, 401, "INVALID_CREDENTIALS");
        verify(tickets, never()).issue(any(), any());
        verify(jwtTokens, never()).issue(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"USER,ADMIN,true", "ADMIN,USER,true", "USER,ADMIN,false", "ADMIN,USER,false"})
    void should_reject_role_change_even_if_version_is_unchanged(UserRole before, UserRole after, boolean increment)
            throws Exception {
        UserEntity user = save(before, 0);
        var verified = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        pauseAfterPasswordVerification(verified, resume);
        MvcResult result = during(() -> login(passwordBody(LoginType.USERNAME_PASSWORD)), verified, resume,
                () -> jdbc.update(increment
                        ? "update users set user_role = ?, token_version = token_version + 1 where id = ?"
                        : "update users set user_role = ? where id = ?", after.name(), user.getId()));
        problem(result, 401, "INVALID_CREDENTIALS");
        verify(tickets, never()).issue(any(), any());
        verify(jwtTokens, never()).issue(any(), any(), any());
    }

    @Test
    void should_report_invalid_credentials_when_account_disappears_after_verification() throws Exception {
        UserEntity user = save(UserRole.USER, 0);
        var verified = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        pauseAfterPasswordVerification(verified, resume);
        problem(during(() -> login(passwordBody(LoginType.USERNAME_PASSWORD)), verified, resume,
                () -> users.deleteById(user.getId())), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void should_reject_revocation_after_code_consumption_without_restoring_code() throws Exception {
        UserEntity user = save(UserRole.USER, 0);
        String code = sendCode();
        var consumed = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = real(codes, invocation);
            assertEquals(true, result);
            consumed.countDown();
            await(resume);
            return result;
        }).when(codes).consume(any(VerifiedEmailCode.class));
        problem(during(() -> login(codeBody(code)), consumed, resume, () -> bump(user.getId())),
                401, "INVALID_CREDENTIALS");
        problem(login(codeBody(code)), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void should_keep_unconsumed_email_code_usable_after_version_change() throws Exception {
        UserEntity user = save(UserRole.USER, 0);
        String code = sendCode();
        bump(user.getId());
        MvcResult result = login(codeBody(code));
        assertEquals(200, result.getResponse().getStatus());
        var token = decoder.decode(tree(result).path("authentication").path("accessToken").asText());
        assertEquals(1L, ((Number) token.getClaim("tokenVersion")).longValue());
    }

    @Test
    void should_reject_issued_ticket_after_version_change_and_consume_it_once() throws Exception {
        UserEntity user = save(UserRole.ADMIN, 0);
        String ticket = ticketFromLogin();
        bump(user.getId());
        problem(login(selectionBody(ticket, UserRole.ADMIN)), 401, "LOGIN_TICKET_INVALID");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(ticketKey(ticket))));
        problem(login(selectionBody(ticket, UserRole.ADMIN)), 401, "LOGIN_TICKET_INVALID");
        verify(jwtTokens, never()).issue(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "extra", "missing", "no_ttl", "expired", "wrong_type"})
    void should_reject_malformed_or_unbounded_real_redis_ticket(String kind) throws Exception {
        UserEntity user = save(UserRole.ADMIN, 0);
        String ticket = "test-ticket-" + kind;
        String key = ticketKey(ticket);
        var fields = new java.util.HashMap<>(Map.of("userId", user.getId().toString(), "maximumRole", "ADMIN",
                "loginType", "USERNAME_PASSWORD", "tokenVersion", "0"));
        if (kind.equals("legacy")) fields.remove("tokenVersion");
        if (kind.equals("missing")) fields.remove("maximumRole");
        if (kind.equals("extra")) fields.put("unknown", "value");
        if (kind.equals("wrong_type")) redis.opsForValue().set(key, "invalid");
        else redis.opsForHash().putAll(key, fields);
        if (!kind.equals("no_ttl")) redis.expire(key, kind.equals("expired") ? Duration.ZERO : Duration.ofSeconds(300));
        problem(login(selectionBody(ticket, UserRole.ADMIN)), 401, "LOGIN_TICKET_INVALID");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "+1", "-1", "007", "00", "1.0", "1e2", "١", "9223372036854775808"})
    void should_reject_noncanonical_version_from_real_redis(String version) throws Exception {
        UserEntity user = save(UserRole.ADMIN, 0);
        String ticket = "test-version-ticket";
        String key = ticketKey(ticket);
        redis.opsForHash().putAll(key, Map.of("userId", user.getId().toString(), "maximumRole", "ADMIN",
                "loginType", "EMAIL_PASSWORD", "tokenVersion", version));
        redis.expire(key, Duration.ofSeconds(300));
        problem(login(selectionBody(ticket, UserRole.USER)), 401, "LOGIN_TICKET_INVALID");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
    }

    @Test
    void should_round_trip_maximum_long_version_without_defaulting_or_incrementing() throws Exception {
        UserEntity user = save(UserRole.ADMIN, Long.MAX_VALUE);
        String ticket = ticketFromLogin();
        MvcResult result = login(selectionBody(ticket, UserRole.ADMIN));
        assertEquals(200, result.getResponse().getStatus());
        String token = tree(result).path("authentication").path("accessToken").asText();
        assertEquals(Long.MAX_VALUE, ((Number) decoder.decode(token).getClaim("tokenVersion")).longValue());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
    }

    @Test
    void should_allow_only_one_concurrent_ticket_redemption() throws Exception {
        save(UserRole.ADMIN, 0);
        String ticket = ticketFromLogin();
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        Callable<Integer> redeem = () -> {
            start.await(15, TimeUnit.SECONDS);
            return login(selectionBody(ticket, UserRole.USER)).getResponse().getStatus();
        };
        try {
            Future<Integer> one = executor.submit(redeem);
            Future<Integer> two = executor.submit(redeem);
            List<Integer> results = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(value -> value == 200).count());
            assertEquals(1, results.stream().filter(value -> value == 401).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void should_sign_only_old_version_if_revoked_after_final_snapshot(UserRole role) throws Exception {
        UserEntity user = save(role, 0);
        var beforeEncoding = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            beforeEncoding.countDown();
            await(resume);
            return real(jwtTokens, invocation);
        }).when(jwtTokens).issue(any(), any(), any());
        Callable<MvcResult> request = () -> role == UserRole.ADMIN
                ? login(selectionBody(ticketFromLogin(), UserRole.ADMIN)) : login(passwordBody(LoginType.USERNAME_PASSWORD));
        MvcResult result = during(request, beforeEncoding, resume, () -> bump(user.getId()));
        assertEquals(200, result.getResponse().getStatus());
        String token = tree(result).path("authentication").path("accessToken").asText();
        assertEquals(0L, ((Number) decoder.decode(token).getClaim("tokenVersion")).longValue());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_publish_old_ticket_version_if_revoked_after_initial_snapshot() throws Exception {
        UserEntity user = save(UserRole.ADMIN, 0);
        var beforePublishing = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            beforePublishing.countDown();
            await(resume);
            return real(tickets, invocation);
        }).when(tickets).issue(any(), any());
        MvcResult result = during(() -> login(passwordBody(LoginType.USERNAME_PASSWORD)), beforePublishing, resume,
                () -> bump(user.getId()));
        assertEquals(200, result.getResponse().getStatus());
        String ticket = tree(result).path("roleSelection").path("loginTicket").asText();
        assertEquals("0", redis.opsForHash().get(ticketKey(ticket), "tokenVersion"));
        problem(login(selectionBody(ticket, UserRole.ADMIN)), 401, "LOGIN_TICKET_INVALID");
    }

    @Test
    void should_not_restore_ticket_when_state_query_fails_after_consumption() throws Exception {
        UserEntity user = save(UserRole.ADMIN, 0);
        String ticket = ticketFromLogin();
        doThrow(new AuthStateStoreUnavailableException("state unavailable", new IllegalStateException("database")))
                .when(states).findSnapshot(user.getId());
        problem(login(selectionBody(ticket, UserRole.ADMIN)), 503, "AUTH_STATE_STORE_UNAVAILABLE");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(ticketKey(ticket))));
        problem(login(selectionBody(ticket, UserRole.ADMIN)), 401, "LOGIN_TICKET_INVALID");
    }

    @Test
    void should_not_restore_code_when_state_query_fails_after_consumption() throws Exception {
        UserEntity user = save(UserRole.USER, 0);
        String code = sendCode();
        doThrow(new AuthStateStoreUnavailableException("state unavailable", new IllegalStateException("database")))
                .when(states).findSnapshot(user.getId());
        problem(login(codeBody(code)), 503, "AUTH_STATE_STORE_UNAVAILABLE");
        problem(login(codeBody(code)), 401, "INVALID_CREDENTIALS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue", "consume"})
    @SuppressWarnings("unchecked")
    void should_reject_ticket_redis_failure_without_fallback_issuance(String operation) throws Exception {
        save(UserRole.ADMIN, 0);
        String ticket = operation.equals("consume") ? ticketFromLogin() : null;
        doAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            if (keys.getFirst().startsWith("music:auth:login-ticket:")) {
                throw new DataAccessResourceFailureException("redis unavailable");
            }
            return real(redis, invocation);
        }).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        problem(login(operation.equals("consume") ? selectionBody(ticket, UserRole.ADMIN)
                : passwordBody(LoginType.USERNAME_PASSWORD)), 503, "AUTH_STATE_STORE_UNAVAILABLE");
        verify(jwtTokens, never()).issue(any(), any(), any());
    }

    private void pauseAfterPasswordVerification(CountDownLatch reached, CountDownLatch resume) {
        doAnswer(invocation -> {
            Object result = real(hasher, invocation);
            assertEquals(true, result);
            reached.countDown();
            await(resume);
            return result;
        }).when(hasher).matches(eq("correct-password"), anyString());
    }

    private <T> T during(Callable<T> request, CountDownLatch reached, CountDownLatch resume, Runnable mutation)
            throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<T> future = executor.submit(request);
            assertTrue(reached.await(15, TimeUnit.SECONDS), "Request did not reach the controlled window");
            mutation.run();
            resume.countDown();
            return future.get(20, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Controlled window was not released");
    }

    private Object real(Object spy, InvocationOnMock invocation) throws Throwable {
        return mockingDetails(spy).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private UserEntity save(UserRole role, long version) {
        UserEntity user = users.save(new UserEntity("alice", "alice@example.com", hasher.hash("correct-password"), role));
        jdbc.update("update users set token_version = ? where id = ?", version, user.getId());
        return user;
    }

    private void bump(Integer userId) {
        jdbc.update("update users set token_version = token_version + 1 where id = ?", userId);
    }

    private String sendCode() {
        sendCodes.send(new EmailSendCodeRequest("alice@example.com", VerificationCodePurpose.LOGIN), "127.0.0.1");
        ArgumentCaptor<String> capture = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("alice@example.com"), eq(VerificationCodePurpose.LOGIN), capture.capture(), any(Instant.class));
        return capture.getValue();
    }

    private String ticketFromLogin() throws Exception {
        MvcResult result = login(passwordBody(LoginType.USERNAME_PASSWORD));
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("ROLE_SELECTION_REQUIRED", tree(result).path("status").asText());
        return tree(result).path("roleSelection").path("loginTicket").asText();
    }

    private MvcResult login(String body) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private JsonNode tree(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void problem(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, result.getResponse().getContentType());
        assertEquals(code, tree(result).path("code").asText());
        assertEquals("urn:problem:music:" + code.toLowerCase(), tree(result).path("type").asText());
    }

    private String ticketKey(String ticket) throws Exception {
        return "music:auth:login-ticket:" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));
    }

    private String passwordBody(LoginType type) {
        return "{\"loginType\":\"" + type.name() + "\",\"account\":\""
                + (type == LoginType.EMAIL_PASSWORD ? "alice@example.com" : "alice") + "\",\"password\":\"correct-password\"}";
    }

    private String codeBody(String code) {
        return "{\"loginType\":\"EMAIL_CODE\",\"email\":\"alice@example.com\",\"code\":\"" + code + "\"}";
    }

    private String selectionBody(String ticket, UserRole role) {
        return "{\"loginType\":\"ROLE_SELECTION\",\"loginTicket\":\"" + ticket + "\",\"loginRole\":\"" + role.name() + "\"}";
    }
}
