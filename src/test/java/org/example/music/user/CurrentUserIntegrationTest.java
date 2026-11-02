package org.example.music.user;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.music.security.JwtTokenService;
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
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class CurrentUserIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("music");

    @Autowired private MockMvc mvc;
    @MockitoSpyBean private UserRepository users;
    @Autowired private UserRoleHistoryRepository history;
    @Autowired private JwtTokenService tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRoleAuthorizationService roles;
    @Value("${app.jwt.issuer}") private String issuer;
    @Value("${app.jwt.audience}") private String audience;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void reset() {
        history.deleteAll();
        users.deleteAll();
    }

    @ParameterizedTest
    @CsvSource({"USER,USER", "ADMIN,ADMIN", "ADMIN,USER"})
    void should_return_current_account_through_real_jwt_filter_chain(UserRole accountRole, UserRole loginRole)
            throws Exception {
        UserEntity user = users.save(new UserEntity("alice", "alice@example.com", "private-password-hash", accountRole));
        jdbc.update("update users set created_at = '2026-10-21 12:34:56' where id = ?", user.getId());
        mvc.perform(request(issue(user, loginRole)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.userRole").value(accountRole.name()))
                .andExpect(jsonPath("$.loginRole").value(loginRole.name()))
                .andExpect(jsonPath("$.createdAt").value("2026-10-21T12:34:56Z"))
                .andExpect(content().string(not(containsString("private-password-hash"))));
    }

    @Test
    void should_preserve_legacy_nulls() throws Exception {
        UserEntity user = users.save(new UserEntity("legacy", null, "hash", UserRole.USER));
        mvc.perform(request(issue(user, UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$", hasKey("email"))).andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$", hasKey("createdAt"))).andExpect(jsonPath("$.createdAt").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-21 12:34:56.000001", "2026-10-21 12:34:56.123456"})
    void should_preserve_database_utc_microseconds_without_jvm_timezone_shift(String storedTime) throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        jdbc.update("update users set created_at = ? where id = ?", storedTime, user.getId());
        mvc.perform(request(issue(user, UserRole.USER))).andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value(storedTime.replace(' ', 'T') + "Z"));
    }

    @Test
    void should_ignore_forged_identity_parameters_headers_and_body() throws Exception {
        UserEntity actor = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        UserEntity other = users.save(UserEntity.createAdmin("other", "other@example.com", "hash"));
        mvc.perform(request(issue(actor, UserRole.USER)).param("userId", other.getId().toString(), "0")
                        .param("id", other.getId().toString()).param("loginRole", "ADMIN", "ADMIN")
                        .param("role", "ADMIN").header("X-User-Id", other.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + other.getId() + ",\"loginRole\":\"ADMIN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(actor.getId()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.userRole").value("USER"))
                .andExpect(jsonPath("$.loginRole").value("USER"));
    }

    @Test
    void should_not_write_account_on_repeated_requests() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        var before = jdbc.queryForMap("select * from users where id = ?", user.getId());
        String token = issue(user, UserRole.USER);
        for (int i = 0; i < 2; i++) {
            mvc.perform(request(token)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertEquals(before, jdbc.queryForMap("select * from users where id = ?", user.getId()));
    }

    @Test
    void should_reject_missing_authentication() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void should_return_auth_state_failure_before_profile_lookup() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        doThrow(new DataAccessResourceFailureException("private database failure"))
                .when(users).findAuthorizationSnapshot(user.getId());
        mvc.perform(request(issue(user, UserRole.USER))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_STORE_UNAVAILABLE"));
        verify(users, never()).findProfileSnapshot(any());
    }

    @Test
    void should_return_revoked_when_profile_disappears_after_real_authentication() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        doReturn(Optional.empty()).when(users).findProfileSnapshot(user.getId());
        mvc.perform(request(issue(user, UserRole.USER))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:token_revoked"));
        verify(users).findAuthorizationSnapshot(user.getId());
    }

    @Test
    void should_return_profile_store_failure_after_real_authentication() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        doThrow(new DataAccessResourceFailureException("private database failure"))
                .when(users).findProfileSnapshot(user.getId());
        mvc.perform(request(issue(user, UserRole.USER))).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USER_PROFILE_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:user_profile_store_unavailable"))
                .andExpect(content().string(not(containsString("private database failure"))));
        verify(users).findAuthorizationSnapshot(user.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "2147483648"})
    void should_reject_invalid_subject(String subject) throws Exception {
        mvc.perform(request(sign(subject, "USER", 0L, Instant.now().plusSeconds(600), encoder)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void should_reject_invalid_version_and_role_claims() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        mvc.perform(request(sign(user.getId().toString(), "USER", -1L, Instant.now().plusSeconds(600), encoder)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(request(sign(user.getId().toString(), "SUPER_ADMIN", 0L, Instant.now().plusSeconds(600), encoder)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void should_revoke_nonexistent_account() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        String token = issue(user, UserRole.USER);
        users.deleteById(user.getId());
        mvc.perform(request(token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"))
                .andExpect(jsonPath("$.type").value("urn:problem:music:token_revoked"));
    }

    @Test
    void should_revoke_old_token_after_role_change() throws Exception {
        UserEntity admin = users.save(UserEntity.createAdmin("admin", "admin@example.com", "hash"));
        UserEntity target = users.save(UserEntity.createAdmin("target", "target@example.com", "hash"));
        String token = issue(target, UserRole.ADMIN);
        roles.updateRole(admin.getId(), UserRole.ADMIN, target.getId(), UserRole.USER);
        mvc.perform(request(token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_revoke_version_mismatch_and_disallowed_login_role() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        mvc.perform(request(sign(user.getId().toString(), "USER", 1L, Instant.now().plusSeconds(600), encoder)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        mvc.perform(request(sign(user.getId().toString(), "ADMIN", 0L, Instant.now().plusSeconds(600), encoder)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_reject_expired_or_wrongly_signed_token_with_resource_server_challenge() throws Exception {
        UserEntity user = users.save(UserEntity.create("alice", "alice@example.com", "hash"));
        mvc.perform(request(sign(user.getId().toString(), "USER", 0L, Instant.now().minusSeconds(120), encoder)))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        JwtEncoder wrongEncoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                "wrong-test-signing-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)));
        mvc.perform(request(sign(user.getId().toString(), "USER", 0L, Instant.now().plusSeconds(600), wrongEncoder)))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    private MockHttpServletRequestBuilder request(String token) {
        return get("/api/v1/users/me").header("Authorization", "Bearer " + token);
    }

    private String issue(UserEntity user, UserRole role) {
        return tokens.issueLegacyWithoutAuthTime(new UserAuthorizationSnapshot(user.getId(), user.getUsername(), user.getEmail(),
                user.getUserRole(), user.getTokenVersion()), role, LoginType.USERNAME_PASSWORD).tokenValue();
    }

    private String sign(String subject, String role, Object version, Instant expiry, JwtEncoder signingEncoder) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(expiry.minusSeconds(600)).expiresAt(expiry)
                .claim("role", role).claim("tokenVersion", version).build();
        return signingEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
