package org.example.music.user;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import org.example.music.security.JwtTokenService;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.repository.UserRoleHistoryRepository;
import org.example.music.user.service.UserRoleAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class CurrentUserUpdateIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
            .withExposedPorts(6379);

    @Autowired private MockMvc mvc;
    @MockitoSpyBean private UserRepository users;
    @Autowired private UserRoleHistoryRepository history;
    @Autowired private JwtTokenService tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private PasswordHasher passwords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private UserRoleAuthorizationService roles;
    @Value("${app.jwt.issuer}") private String issuer;
    @Value("${app.jwt.audience}") private String audience;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> "0");
        registry.add("spring.data.redis.username", () -> "default");
        registry.add("spring.data.redis.password", () -> "");
    }

    @BeforeEach
    void reset() {
        history.deleteAll();
        users.deleteAll();
        var keys = redis.keys("music:auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @ParameterizedTest
    @CsvSource({"USER,USER", "ADMIN,USER", "ADMIN,ADMIN"})
    void should_update_only_public_profile_columns_through_real_jwt_chain(UserRole accountRole, UserRole loginRole)
            throws Exception {
        UserEntity user = save("Alice", accountRole);
        var before = row(user.getId());
        String token = issue(user, loginRole);
        Instant start = databaseNow();
        mvc.perform(change(token, "Alice_new"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.id").value(user.getId())).andExpect(jsonPath("$.username").value("Alice_new"))
                .andExpect(jsonPath("$.email").value("Alice@example.com"))
                .andExpect(jsonPath("$.userRole").value(accountRole.name()))
                .andExpect(jsonPath("$.loginRole").value(loginRole.name()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:34:56.123456Z"));
        var after = row(user.getId());
        assertOnlyProfileColumnsChanged(before, after);
        assertEquals("Alice_new", after.get("username"));
        Instant changedAt = updatedAt(user.getId());
        assertFalse(changedAt.isBefore(start));
        assertFalse(changedAt.isAfter(databaseNow()));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("Alice_new"));
    }

    @Test
    void should_preserve_legacy_null_fields() throws Exception {
        UserEntity user = users.save(new UserEntity("legacy", null, passwords.hash("correct-password"), UserRole.USER));
        mvc.perform(change(issue(user, UserRole.USER), "legacy_new"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$", hasKey("email"))).andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("createdAt"))).andExpect(jsonPath("$.createdAt").value(nullValue()));
    }

    @Test
    void should_change_case_but_not_write_identical_repeated_requests() throws Exception {
        UserEntity user = save("Alice", UserRole.USER);
        String token = issue(user, UserRole.USER);
        var before = row(user.getId());
        mvc.perform(change(token, "Alice")).andExpect(status().isOk());
        mvc.perform(change(token, "Alice")).andExpect(status().isOk());
        assertEquals(before, row(user.getId()));
        mvc.perform(change(token, "alice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
        var after = row(user.getId());
        assertOnlyProfileColumnsChanged(before, after);
        assertNotEquals(before.get("updated_at"), after.get("updated_at"));
        mvc.perform(change(token, "alice")).andExpect(status().isOk());
        assertEquals(after, row(user.getId()));
    }

    @Test
    void should_return_generic_conflict_for_another_accounts_case_variant() throws Exception {
        UserEntity owner = save("Alice", UserRole.USER);
        UserEntity actor = save("other", UserRole.USER);
        var ownerBefore = row(owner.getId());
        var actorBefore = row(actor.getId());
        mvc.perform(change(issue(actor, UserRole.USER), "alice")).andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:username_already_exists"))
                .andExpect(jsonPath("$.detail").value("用户名已占用"));
        assertEquals(ownerBefore, row(owner.getId()));
        assertEquals(actorBefore, row(actor.getId()));
    }

    @Test
    void should_ignore_identity_headers_and_query_parameters_but_reject_identity_body_fields() throws Exception {
        UserEntity actor = save("actor", UserRole.USER);
        UserEntity other = save("other", UserRole.ADMIN);
        var otherBefore = row(other.getId());
        String token = issue(actor, UserRole.USER);
        mvc.perform(change(token, "actor_new").param("userId", other.getId().toString(), "999")
                        .param("loginRole", "ADMIN").header("X-User-Id", other.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(actor.getId()))
                .andExpect(jsonPath("$.loginRole").value("USER"));
        var actorAfter = row(actor.getId());
        mvc.perform(patch("/api/v1/users/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"stolen\",\"userId\":" + other.getId() + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertEquals(actorAfter, row(actor.getId()));
        assertEquals(otherBefore, row(other.getId()));
    }

    @Test
    void should_reject_missing_authentication_before_invalid_body() throws Exception {
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(users, never()).updateUsernameIfChanged(any(), any());
    }

    @Test
    void should_revoke_deleted_accounts_before_update() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        String token = issue(user, UserRole.USER);
        users.deleteById(user.getId());
        mvc.perform(change(token, "alice_new")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        verify(users, never()).updateUsernameIfChanged(any(), any());
    }

    @Test
    void should_roll_back_both_columns_when_profile_lookup_fails_after_real_update() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        var before = row(user.getId());
        doThrow(new DataAccessResourceFailureException("private SQL details")).when(users).findProfileSnapshot(user.getId());
        mvc.perform(change(issue(user, UserRole.USER), "alice_new"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_UPDATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:user_profile_update_unavailable"))
                .andExpect(content().string(not(containsString("private SQL details"))));
        verify(users).updateUsernameIfChanged(user.getId(), "alice_new");
        assertEquals(before, row(user.getId()));
    }

    @Test
    void should_roll_back_when_profile_disappears_after_authentication() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        var before = row(user.getId());
        doReturn(Optional.empty()).when(users).findProfileSnapshot(user.getId());
        mvc.perform(change(issue(user, UserRole.USER), "alice_new"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        assertEquals(before, row(user.getId()));
    }

    @Test
    void should_reject_auth_state_failure_before_any_update() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        var before = row(user.getId());
        doThrow(new DataAccessResourceFailureException("database")).when(users).findAuthorizationSnapshot(user.getId());
        mvc.perform(change(issue(user, UserRole.USER), "alice_new"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_STATE_STORE_UNAVAILABLE"));
        verify(users, never()).updateUsernameIfChanged(any(), any());
        assertEquals(before, row(user.getId()));
    }

    @Test
    void should_allow_only_one_account_to_claim_username_concurrently() throws Exception {
        UserEntity one = save("user_one", UserRole.USER);
        UserEntity two = save("user_two", UserRole.USER);
        var beforeOne = row(one.getId());
        var beforeTwo = row(two.getId());
        var barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return invokeRepository(invocation);
        }).when(users).updateUsernameIfChanged(any(), eq("contested"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            String tokenOne = issue(one, UserRole.USER);
            String tokenTwo = issue(two, UserRole.USER);
            Future<Integer> first = executor.submit(() -> mvc.perform(change(tokenOne, "contested")).andReturn().getResponse().getStatus());
            Future<Integer> second = executor.submit(() -> mvc.perform(change(tokenTwo, "contested")).andReturn().getResponse().getStatus());
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(code -> code == 200).count());
            assertEquals(1, results.stream().filter(code -> code == 409).count());
            assertEquals(1, jdbc.queryForObject("select count(*) from users where username = 'contested'", Integer.class));
            assertOnlyProfileColumnsChanged(beforeOne, row(one.getId()));
            assertOnlyProfileColumnsChanged(beforeTwo, row(two.getId()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void should_serialize_same_user_updates_without_overwriting_security_columns() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        var before = row(user.getId());
        var firstUpdated = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if ("first_name".equals(invocation.getArgument(1))) {
                Object changed = invokeRepository(invocation);
                firstUpdated.countDown();
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
                return changed;
            }
            secondEntered.countDown();
            return invokeRepository(invocation);
        }).when(users).updateUsernameIfChanged(eq(user.getId()), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            String token = issue(user, UserRole.USER);
            Future<Integer> first = executor.submit(() -> mvc.perform(change(token, "first_name")).andReturn().getResponse().getStatus());
            assertTrue(firstUpdated.await(10, TimeUnit.SECONDS));
            Future<Integer> second = executor.submit(() -> mvc.perform(change(token, "second_name")).andReturn().getResponse().getStatus());
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            releaseFirst.countDown();
            assertEquals(200, first.get(20, TimeUnit.SECONDS));
            assertEquals(200, second.get(20, TimeUnit.SECONDS));
            assertEquals("second_name", row(user.getId()).get("username"));
            assertOnlyProfileColumnsChanged(before, row(user.getId()));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void should_login_with_new_username_and_email_using_real_redis_without_revoking_existing_token() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        String token = issue(user, UserRole.USER);
        mvc.perform(change(token, "alice_new")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("USERNAME_PASSWORD", "alice_new")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authentication.user.id").value(user.getId()));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("USERNAME_PASSWORD", "alice")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("EMAIL_PASSWORD", "alice@example.com")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authentication.user.username").value("alice_new"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void should_keep_case_insensitive_login_after_case_only_rename() throws Exception {
        UserEntity user = save("Alice", UserRole.USER);
        mvc.perform(change(issue(user, UserRole.USER), "alice")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("USERNAME_PASSWORD", "Alice")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authentication.user.username").value("alice"));
    }

    @Test
    void should_reject_role_revoked_token_without_restoring_token_version() throws Exception {
        UserEntity admin = save("admin", UserRole.ADMIN);
        UserEntity target = save("target", UserRole.ADMIN);
        String token = issue(target, UserRole.ADMIN);
        roles.updateRole(admin.getId(), UserRole.ADMIN, target.getId(), UserRole.USER);
        var before = row(target.getId());
        mvc.perform(change(token, "target_new")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        assertEquals(before, row(target.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "2147483648"})
    void should_reject_invalid_subject_before_update(String subject) throws Exception {
        mvc.perform(change(sign(subject, "USER", 0L, Instant.now().plusSeconds(600), encoder), "alice_new"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        verify(users, never()).updateUsernameIfChanged(any(), any());
    }

    @Test
    void should_reject_invalid_version_role_expiration_and_signature() throws Exception {
        UserEntity user = save("alice", UserRole.USER);
        mvc.perform(change(sign(user.getId().toString(), "USER", 1L, Instant.now().plusSeconds(600), encoder), "alice_new"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        mvc.perform(change(sign(user.getId().toString(), "ADMIN", 0L, Instant.now().plusSeconds(600), encoder), "alice_new"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        mvc.perform(change(sign(user.getId().toString(), "USER", 0L, Instant.now().minusSeconds(120), encoder), "alice_new"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        JwtEncoder wrong = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                "wrong-test-signing-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(change(sign(user.getId().toString(), "USER", 0L, Instant.now().plusSeconds(600), wrong), "alice_new"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        verify(users, never()).updateUsernameIfChanged(any(), any());
    }

    private UserEntity save(String username, UserRole role) {
        UserEntity user = users.save(new UserEntity(username, username + "@example.com", passwords.hash("correct-password"), role));
        jdbc.update("update users set created_at = '2026-10-21 12:34:56.123456', updated_at = '2000-01-01 00:00:00' where id = ?", user.getId());
        return user;
    }

    private Map<String, Object> row(Integer id) {
        return jdbc.queryForMap("select * from users where id = ?", id);
    }

    private void assertOnlyProfileColumnsChanged(Map<String, Object> before, Map<String, Object> after) {
        var expected = new java.util.HashMap<>(before);
        var actual = new java.util.HashMap<>(after);
        expected.remove("username");
        expected.remove("updated_at");
        actual.remove("username");
        actual.remove("updated_at");
        assertEquals(expected, actual);
    }

    private Instant databaseNow() {
        return asInstant(jdbc.queryForObject("select cast(UTC_TIMESTAMP(6) as char)", String.class));
    }

    private Instant updatedAt(Integer id) {
        return asInstant(jdbc.queryForObject("select cast(updated_at as char) from users where id = ?", String.class, id));
    }

    private Object invokeRepository(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
        // Spring Data 接口的 spy 通过默认 Answer 转发真实代理，不能对抽象方法调用 callRealMethod。
        return mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    private Instant asInstant(String datetime) {
        return LocalDateTime.parse(datetime.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }

    private MockHttpServletRequestBuilder change(String token, String username) {
        return patch("/api/v1/users/me").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username + "\"}");
    }

    private String issue(UserEntity user, UserRole role) {
        return tokens.issue(new UserAuthorizationSnapshot(user.getId(), user.getUsername(), user.getEmail(),
                user.getUserRole(), user.getTokenVersion()), role, LoginType.USERNAME_PASSWORD).tokenValue();
    }

    private String sign(String subject, String role, long version, Instant expiry, JwtEncoder signingEncoder) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(expiry.minusSeconds(600)).expiresAt(expiry).claim("role", role).claim("tokenVersion", version).build();
        return signingEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String loginBody(String type, String account) {
        return "{\"loginType\":\"" + type + "\",\"account\":\"" + account + "\",\"password\":\"correct-password\"}";
    }
}
