package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.example.music.user.exception.PasswordChangeRateLimitedException;
import org.example.music.user.exception.PasswordChangeRateLimitUnavailableException;
import org.example.music.user.service.PasswordChangeRateLimiter;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.music.common.util.SmtpEmailVerificationCodeSender;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.service.EmailSendCodeService;
import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.service.LoginTicketService;
import org.example.music.user.service.UserAuthorizationStateService;
import org.example.music.verification.domain.VerificationCodePurpose;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;
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
    @MockitoSpyBean private StringRedisTemplate redis;
    @Autowired private PasswordChangeRateLimiter limiter;
    @MockitoSpyBean private UserRepository users;
    @MockitoSpyBean private PasswordHasher hasher;
    @MockitoSpyBean private JwtTokenService tokens;
    @MockitoSpyBean private LoginTicketService tickets;
    @MockitoSpyBean private UserAuthorizationStateService states;
    @MockitoBean private SmtpEmailVerificationCodeSender mail;
    @Autowired private MockMvc mvc;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private EmailSendCodeService sendCodes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
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
        users.deleteAll();
        Set<String> keys = redis.keys("music:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private UserEntity saveUser(String password) {
        return users.saveAndFlush(UserEntity.create("alice", "alice@example.com", hasher.hash(password)));
    }

    private String token(UserEntity user, UserRole role) {
        return tokens.issueLegacyWithoutAuthTime(users.findAuthorizationSnapshot(user.getId()).orElseThrow(), role,
                LoginType.USERNAME_PASSWORD).tokenValue();
    }

    private MvcResult change(String token, String oldPassword, String newPassword) throws Exception {
        return mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        Map.of("oldPassword", oldPassword, "newPassword", newPassword)))).andReturn();
    }

    private MvcResult login(Map<String, String> body) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private String field(MvcResult response, String object, String name) throws Exception {
        return json.readTree(response.getResponse().getContentAsString()).path(object).path(name).asText();
    }

    private void problem(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        var body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(code, body.path("code").asText());
        assertEquals("urn:problem:music:" + code.toLowerCase(), body.path("type").asText());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, result.getResponse().getContentType());
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void should_revoke_current_other_device_and_other_role_tokens_after_change(UserRole loginRole) throws Exception {
        var user = saveUser("old-password");
        user.setUserRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        var other = users.saveAndFlush(UserEntity.create("other", "other@example.com", hasher.hash("other-password")));
        var otherBefore = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId());
        String current = token(user, loginRole);
        String second = token(user, UserRole.USER);
        String third = token(user, UserRole.ADMIN);
        var response = change(current, "old-password", " new-password ");
        assertEquals(204, response.getResponse().getStatus());
        assertEquals("", response.getResponse().getContentAsString());
        assertEquals("no-store", response.getResponse().getHeader("Cache-Control"));
        assertEquals(1, users.findPasswordSnapshot(user.getId()).orElseThrow().tokenVersion());
        for (String old : List.of(current, second, third)) {
            problem(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + old)).andReturn(),
                    401, "TOKEN_REVOKED");
        }
        problem(change(current, "old-password", "other-password"), 401, "TOKEN_REVOKED");
        problem(login(Map.of("loginType", "USERNAME_PASSWORD", "account", "alice", "password", "old-password")),
                401, "INVALID_CREDENTIALS");
        var selected = login(Map.of("loginType", "USERNAME_PASSWORD", "account", "alice", "password", " new-password "));
        var fresh = login(Map.of("loginType", "ROLE_SELECTION", "loginTicket", field(selected, "roleSelection", "loginTicket"),
                "loginRole", "USER"));
        assertEquals(200, fresh.getResponse().getStatus());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + field(fresh, "authentication", "accessToken")))
                .andExpect(status().isOk());
        assertEquals(otherBefore, jdbc.queryForMap("SELECT * FROM users WHERE id = ?", other.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "abcdefg", "历史-password"})
    void should_accept_compatible_old_password_without_applying_creation_rules(String old) throws Exception {
        var user = saveUser(old);
        assertEquals(204, change(token(user, UserRole.USER), old, "new-password").getResponse().getStatus());
        assertTrue(hasher.matches("new-password", users.findPasswordSnapshot(user.getId()).orElseThrow().passwordHash()));
    }

    @Test
    void should_validate_before_rate_limit_and_preserve_actor_identity() throws Exception {
        var user = saveUser("old-password");
        String current = token(user, UserRole.USER);
        for (String body : List.of("{}", "null", "{\"oldPassword\":\"old-password\",\"newPassword\":\"new-password\",\"userId\":99}",
                json.writeValueAsString(Map.of("oldPassword", "old-password", "newPassword", "a".repeat(73))),
                json.writeValueAsString(Map.of("oldPassword", "old-password", "newPassword", "abcdefgh😀")))) {
            clearInvocations(hasher);
            problem(mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + current)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 400, "VALIDATION_FAILED");
            verifyNoInteractions(hasher);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(rateKey(user.getId()))));
        }
        var response = mvc.perform(put("/api/v1/users/me/password").header("Authorization", "Bearer " + current)
                .param("userId", "99").header("X-User-Id", "99").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("oldPassword", "old-password", "newPassword", "new-password")))).andReturn();
        assertEquals(204, response.getResponse().getStatus());
        assertTrue(hasher.matches("new-password", users.findPasswordSnapshot(user.getId()).orElseThrow().passwordHash()));
    }

    @Test
    void should_preserve_counter_across_tokens_failures_success_and_login() throws Exception {
        var user = saveUser("old-password");
        String first = token(user, UserRole.USER);
        String second = token(user, UserRole.USER);
        for (int i = 0; i < 3; i++) problem(change(i % 2 == 0 ? first : second, "wrong-password", "new-password"), 400, "OLD_PASSWORD_INCORRECT");
        problem(change(second, "old-password", "old-password"), 400, "NEW_PASSWORD_SAME_AS_OLD");
        long before = redis.getExpire(rateKey(user.getId()), TimeUnit.MILLISECONDS);
        assertEquals(204, change(first, "old-password", "new-password").getResponse().getStatus());
        var fresh = login(Map.of("loginType", "USERNAME_PASSWORD", "account", "alice", "password", "new-password"));
        assertEquals(200, fresh.getResponse().getStatus());
        var response = change(field(fresh, "authentication", "accessToken"), "new-password", "next-password");
        problem(response, 429, "PASSWORD_CHANGE_RATE_LIMITED");
        assertTrue(Long.parseLong(response.getResponse().getHeader("Retry-After")) > 0);
        assertEquals("5", redis.opsForValue().get(rateKey(user.getId())));
        assertTrue(redis.getExpire(rateKey(user.getId()), TimeUnit.MILLISECONDS) <= before);
    }

    @Test
    void should_limit_concurrent_http_attempts_before_bcrypt() throws Exception {
        var user = saveUser("old-password");
        String current = token(user, UserRole.USER);
        clearInvocations(hasher);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        try {
            var futures = new java.util.ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(15, TimeUnit.SECONDS));
                return change(current, "wrong-password", "new-password");
            }));
            start.countDown();
            int verified = 0;
            int rejected = 0;
            for (var future : futures) {
                var result = future.get(30, TimeUnit.SECONDS);
                if (result.getResponse().getStatus() == 400) {
                    problem(result, 400, "OLD_PASSWORD_INCORRECT");
                    verified++;
                } else {
                    problem(result, 429, "PASSWORD_CHANGE_RATE_LIMITED");
                    rejected++;
                }
            }
            assertEquals(5, verified);
            assertEquals(7, rejected);
            verify(hasher, times(5)).matches(eq("wrong-password"), anyString());
            verify(hasher, never()).hash(anyString());
            assertEquals(0, users.findPasswordSnapshot(user.getId()).orElseThrow().tokenVersion());
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void should_return_old_password_error_for_nonmatching_long_historical_input() throws Exception {
        var user = saveUser("old-password");
        String candidate = "汉".repeat(40);
        assertFalse(hasher.matches(candidate, user.getPasswordHash()));
        problem(change(token(user, UserRole.USER), candidate, "new-password"), 400, "OLD_PASSWORD_INCORRECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "update", "conflict", "missing", "deleted_after_hash", "max_version", "redis", "corrupt"})
    void should_fail_without_fake_success_and_keep_consumed_attempt(String scenario) throws Exception {
        var user = saveUser("old-password");
        if (scenario.equals("max_version")) jdbc.update("UPDATE users SET token_version = ? WHERE id = ?", Long.MAX_VALUE, user.getId());
        var before = users.findPasswordSnapshot(user.getId()).orElseThrow();
        String current = token(user, UserRole.USER);
        switch (scenario) {
            case "read" -> doThrow(new DataAccessResourceFailureException("private SQL")).when(users).findPasswordSnapshot(user.getId());
            case "update" -> doThrow(new DataAccessResourceFailureException("private digest")).when(users)
                    .changePasswordIfSnapshotMatches(eq(user.getId()), anyString(), anyLong(), anyString());
            case "conflict" -> doAnswer(invocation -> {
                jdbc.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", user.getId());
                return real(hasher, invocation);
            }).when(hasher).hash("new-password");
            case "missing" -> doAnswer(invocation -> {
                users.deleteById(user.getId());
                return java.util.Optional.empty();
            }).when(users).findPasswordSnapshot(user.getId());
            case "deleted_after_hash" -> doAnswer(invocation -> {
                users.deleteById(user.getId());
                return real(hasher, invocation);
            }).when(hasher).hash("new-password");
            case "redis" -> doThrow(new DataAccessResourceFailureException("private Redis"))
                    .when(redis).execute(any(RedisScript.class), eq(List.of(rateKey(user.getId()))));
            case "corrupt" -> redis.opsForValue().set(rateKey(user.getId()), "01", Duration.ofSeconds(900));
        }
        clearInvocations(hasher);
        var response = change(current, "old-password", "new-password");
        String code = switch (scenario) {
            case "conflict" -> "PASSWORD_CHANGE_CONFLICT";
            case "missing", "deleted_after_hash" -> "TOKEN_REVOKED";
            case "redis", "corrupt" -> "PASSWORD_CHANGE_RATE_LIMIT_UNAVAILABLE";
            default -> "PASSWORD_CHANGE_STORE_UNAVAILABLE";
        };
        boolean deleted = scenario.equals("missing") || scenario.equals("deleted_after_hash");
        problem(response, scenario.equals("conflict") ? 409 : deleted ? 401 : 503, code);
        if (scenario.equals("redis") || scenario.equals("corrupt")) verifyNoInteractions(hasher);
        else assertEquals("1", redis.opsForValue().get(rateKey(user.getId())));
        if (!deleted) {
            assertEquals(before.passwordHash(), jdbc.queryForObject("SELECT password_token FROM users WHERE id = ?", String.class, user.getId()));
        }
    }

    private Object real(Object spy, InvocationOnMock invocation) throws Throwable {
        return mockingDetails(spy).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    @Test
    void should_allow_only_one_cas_for_concurrent_password_changes() throws Exception {
        var user = saveUser("old-password");
        String current = token(user, UserRole.USER);
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(20, TimeUnit.SECONDS);
            return real(hasher, invocation);
        }).when(hasher).hash("new-password");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> change(current, "old-password", "new-password"));
            var two = pool.submit(() -> change(current, "old-password", "new-password"));
            var responses = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertEquals(List.of(204, 409), responses.stream().map(r -> r.getResponse().getStatus()).sorted().toList());
            for (var response : responses) if (response.getResponse().getStatus() == 409) problem(response, 409, "PASSWORD_CHANGE_CONFLICT");
            assertEquals(1, users.findPasswordSnapshot(user.getId()).orElseThrow().tokenVersion());
            assertTrue(hasher.matches("new-password", users.findPasswordSnapshot(user.getId()).orElseThrow().passwordHash()));
            assertEquals("2", redis.opsForValue().get(rateKey(user.getId())));
        } finally {
            barrier.reset();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private MvcResult duringPausedRequest(Callable<MvcResult> request, CountDownLatch reached,
            CountDownLatch resume, org.junit.jupiter.api.function.Executable mutation) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(request);
            assertTrue(reached.await(20, TimeUnit.SECONDS), "Controlled request did not reach boundary");
            assertDoesNotThrow(mutation);
            resume.countDown();
            return future.get(30, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void should_not_overwrite_a_concurrent_role_version_change() throws Exception {
        var user = saveUser("old-password");
        String current = token(user, UserRole.USER);
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            var result = real(hasher, invocation);
            reached.countDown();
            assertTrue(resume.await(20, TimeUnit.SECONDS));
            return result;
        }).when(hasher).hash("new-password");
        var response = duringPausedRequest(() -> change(current, "old-password", "new-password"), reached, resume,
                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> assertEquals(1,
                        users.updateRoleAndIncrementTokenVersion(user.getId(), UserRole.USER, UserRole.ADMIN,
                                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)))));
        problem(response, 409, "PASSWORD_CHANGE_CONFLICT");
        var after = users.findById(user.getId()).orElseThrow();
        assertEquals(UserRole.ADMIN, after.getUserRole());
        assertEquals(1, after.getTokenVersion());
        assertTrue(hasher.matches("old-password", after.getPasswordHash()));
        assertEquals("1", redis.opsForValue().get(rateKey(user.getId())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"verified_password", "issued_ticket", "consumed_ticket", "final_snapshot"})
    void should_prevent_old_verification_and_ticket_from_upgrading_after_real_change(String window) throws Exception {
        var user = saveUser("old-password");
        if (window.contains("ticket")) {
            user.setUserRole(UserRole.ADMIN);
            users.saveAndFlush(user);
        }
        String current = token(user, UserRole.USER);
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        String ticket;
        if (window.equals("consumed_ticket")) {
            ticket = field(login(Map.of("loginType", "USERNAME_PASSWORD", "account", "alice", "password", "old-password")),
                    "roleSelection", "loginTicket");
            doAnswer(invocation -> {
                Object result = real(tickets, invocation);
                reached.countDown();
                assertTrue(resume.await(20, TimeUnit.SECONDS));
                return result;
            }).when(tickets).consume(ticket);
        } else ticket = null;
        if (window.equals("verified_password")) {
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(invocation -> {
                Object result = real(hasher, invocation);
                if (once.compareAndSet(false, true)) {
                    reached.countDown();
                    assertTrue(resume.await(20, TimeUnit.SECONDS));
                }
                return result;
            }).when(hasher).matches(eq("old-password"), anyString());
        }
        if (window.equals("final_snapshot")) {
            doAnswer(invocation -> {
                reached.countDown();
                assertTrue(resume.await(20, TimeUnit.SECONDS));
                return real(tokens, invocation);
            }).when(tokens).issue(any(), eq(UserRole.USER), eq(LoginType.USERNAME_PASSWORD), any());
        }
        Callable<MvcResult> pending = () -> login(ticket == null
                ? Map.of("loginType", "USERNAME_PASSWORD", "account", "alice", "password", "old-password")
                : Map.of("loginType", "ROLE_SELECTION", "loginTicket", ticket, "loginRole", "USER"));
        MvcResult response;
        if (window.equals("issued_ticket")) {
            response = pending.call();
            assertEquals(204, change(current, "old-password", "new-password").getResponse().getStatus());
            response = login(Map.of("loginType", "ROLE_SELECTION", "loginTicket", field(response, "roleSelection", "loginTicket"),
                    "loginRole", "USER"));
        } else {
            response = duringPausedRequest(pending, reached, resume,
                    () -> assertEquals(204, change(current, "old-password", "new-password").getResponse().getStatus()));
        }
        if (window.equals("final_snapshot")) {
            assertEquals(200, response.getResponse().getStatus());
            String late = field(response, "authentication", "accessToken");
            assertEquals(0, ((Number) decoder.decode(late).getClaim("tokenVersion")).longValue());
            problem(mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + late)).andReturn(), 401, "TOKEN_REVOKED");
        } else problem(response, 401, window.equals("verified_password") ? "INVALID_CREDENTIALS" : "LOGIN_TICKET_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "expired", "signature", "subject", "role", "version", "state"})
    void should_reject_invalid_authentication_before_password_change(String kind) throws Exception {
        var user = saveUser("old-password");
        var before = users.findPasswordSnapshot(user.getId()).orElseThrow();
        var claims = JwtClaimsSet.builder().issuer("music-api").audience(List.of("music-client"))
                .subject(kind.equals("subject") ? "2147483648" : user.getId().toString())
                .issuedAt(Instant.now().minusSeconds(600))
                .expiresAt(kind.equals("expired") ? Instant.now().minusSeconds(300) : Instant.now().plusSeconds(300))
                .claim("role", kind.equals("role") ? "UNKNOWN" : "USER")
                .claim("tokenVersion", kind.equals("version") ? -1L : 0L).build();
        String signed = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        if (kind.equals("signature")) {
            int start = signed.lastIndexOf('.') + 1;
            signed = signed.substring(0, start) + (signed.charAt(start) == 'A' ? 'B' : 'A') + signed.substring(start + 1);
        }
        if (kind.equals("state")) doThrow(new AuthStateStoreUnavailableException("unavailable", null)).when(states).findSnapshot(user.getId());
        clearInvocations(hasher);
        var request = put("/api/v1/users/me/password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("oldPassword", "old-password", "newPassword", "new-password")));
        if (!kind.equals("missing")) request.header("Authorization", "Bearer " + signed);
        var result = mvc.perform(request).andReturn();
        assertEquals(kind.equals("state") ? 503 : 401, result.getResponse().getStatus());
        if (kind.equals("state")) problem(result, 503, "AUTH_STATE_STORE_UNAVAILABLE");
        assertEquals(before, users.findPasswordSnapshot(user.getId()).orElseThrow());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(rateKey(user.getId()))));
        verifyNoInteractions(hasher);
    }

    @Test
    void should_keep_email_code_login_available_after_change() throws Exception {
        var user = saveUser("old-password");
        assertEquals(204, change(token(user, UserRole.USER), "old-password", "new-password").getResponse().getStatus());
        sendCodes.send(new EmailSendCodeRequest("alice@example.com", VerificationCodePurpose.LOGIN), "127.0.0.1");
        var capture = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("alice@example.com"), eq(VerificationCodePurpose.LOGIN), capture.capture(), any(Instant.class));
        var result = login(Map.of("loginType", "EMAIL_CODE", "email", "alice@example.com", "code", capture.getValue()));
        assertEquals(200, result.getResponse().getStatus());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + field(result, "authentication", "accessToken")))
                .andExpect(status().isOk());
    }

    @Test
    void should_update_only_password_version_and_utc_timestamp() {
        var user = saveUser("old-password");
        jdbc.update("UPDATE users SET updated_at = '2020-01-01 00:00:00' WHERE id = ?", user.getId());
        var before = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", user.getId());
        var snapshot = users.findPasswordSnapshot(user.getId()).orElseThrow();
        var hash = hasher.hash("new-password");
        var tx = new TransactionTemplate(transactions);
        int changed = tx.execute(status -> users.changePasswordIfSnapshotMatches(user.getId(),
                snapshot.passwordHash(), snapshot.tokenVersion(), hash));
        assertEquals(1, changed);
        var after = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", user.getId());
        assertTrue(hasher.matches("new-password", (String) after.get("password_token")));
        assertFalse(hasher.matches("old-password", (String) after.get("password_token")));
        assertEquals(1L, ((Number) after.get("token_version")).longValue());
        String updated = jdbc.queryForObject("SELECT CAST(updated_at AS CHAR) FROM users WHERE id = ?", String.class, user.getId());
        var utc = java.time.LocalDateTime.parse(updated.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC);
        assertTrue(Duration.between(utc, java.time.Instant.now()).abs().getSeconds() < 30);
        for (var column : before.keySet()) {
            if (!Set.of("password_token", "token_version", "updated_at").contains(column)) {
                assertEquals(before.get(column), after.get(column), column);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "hash", "version", "max_version"})
    void should_reject_independently_mismatched_cas_conditions(String mismatch) {
        var user = saveUser("old-password");
        var snapshot = users.findPasswordSnapshot(user.getId()).orElseThrow();
        String hash = snapshot.passwordHash();
        if (mismatch.equals("hash")) {
            // Same digest bytes except an ASCII letter's case: the default DB collation would ignore it.
            int index = 0;
            while (!Character.isLetter(hash.charAt(index))) index++;
            char original = hash.charAt(index);
            char changed = Character.isUpperCase(original) ? Character.toLowerCase(original) : Character.toUpperCase(original);
            hash = hash.substring(0, index) + changed + hash.substring(index + 1);
        }
        if (mismatch.equals("max_version")) jdbc.update("UPDATE users SET token_version = ? WHERE id = ?", Long.MAX_VALUE, user.getId());
        String previous = hash;
        var tx = new TransactionTemplate(transactions);
        int changed = tx.execute(status -> users.changePasswordIfSnapshotMatches(
                mismatch.equals("id") ? -1 : user.getId(), previous,
                mismatch.equals("version") ? 1 : mismatch.equals("max_version") ? Long.MAX_VALUE : 0, "not-written"));
        assertEquals(0, changed);
        assertEquals(snapshot.passwordHash(), users.findPasswordSnapshot(user.getId()).orElseThrow().passwordHash());
    }

    @Test
    void should_roll_back_hash_and_version_together() {
        var user = saveUser("old-password");
        var before = users.findPasswordSnapshot(user.getId()).orElseThrow();
        var tx = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            assertEquals(1, users.changePasswordIfSnapshotMatches(user.getId(), before.passwordHash(), 0, "not-committed"));
            throw new IllegalStateException("controlled rollback");
        }));
        assertEquals(before, users.findPasswordSnapshot(user.getId()).orElseThrow());
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

    @Test
    void should_preserve_shorter_existing_ttl_when_allowing_an_attempt() {
        redis.opsForValue().set(rateKey(42), "1", Duration.ofSeconds(30));
        long before = redis.getExpire(rateKey(42), TimeUnit.MILLISECONDS);
        limiter.consumeOrThrow(42);
        long after = redis.getExpire(rateKey(42), TimeUnit.MILLISECONDS);
        assertEquals("2", redis.opsForValue().get(rateKey(42)));
        assertTrue(after > 0 && after <= before);
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
