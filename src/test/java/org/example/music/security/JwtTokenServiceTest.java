package org.example.music.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.RoleSelectionNotAllowedException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

class JwtTokenServiceTest {

    private JwtTokenService service(java.time.Clock clock) {
        var key = new SecretKeySpec("test-secret-that-is-long-enough-1234567890".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new JwtTokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), "music-api", "music-client", 86400, clock);
    }

    @Test
    void should_preserve_original_authentication_time_in_integer_wire_claim() {
        Instant now = Instant.parse("2026-11-02T12:00:00.500Z");
        Instant verified = now.minusSeconds(600);
        var service = service(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var snapshot = new UserAuthorizationSnapshot(42, "alice", null, UserRole.USER, 7);
        var result = service.issue(snapshot, UserRole.USER, LoginType.EMAIL_CODE, verified);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(result.tokenValue().split("\\.")[1]), StandardCharsets.UTF_8);
        var tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload);
        assertTrue(tree.path("auth_time").isIntegralNumber());
        assertEquals(verified.getEpochSecond(), tree.path("auth_time").asLong());
        assertEquals(now.getEpochSecond(), tree.path("iat").asLong());
        assertEquals(86400, tree.path("exp").asLong() - tree.path("iat").asLong());
        var legacy = service.issueLegacyWithoutAuthTime(snapshot, UserRole.USER, LoginType.EMAIL_CODE);
        var old = new String(java.util.Base64.getUrlDecoder().decode(legacy.tokenValue().split("\\.")[1]), StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(tools.jackson.databind.json.JsonMapper.builder().build().readTree(old).has("auth_time"));
    }

    @Test
    void should_reject_missing_negative_or_future_verified_time_including_subsecond_future() {
        Instant now = Instant.parse("2026-11-02T12:00:00.500Z");
        var service = service(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        var snapshot = new UserAuthorizationSnapshot(42, "alice", null, UserRole.USER, 7);
        for (Instant verified : new Instant[]{null, Instant.EPOCH.minusNanos(1), now.plusNanos(1), now.plusSeconds(1)}) {
            assertThrows(IllegalArgumentException.class, () -> service.issue(snapshot, UserRole.USER, LoginType.USERNAME_PASSWORD, verified));
        }
        assertThrows(RoleSelectionNotAllowedException.class,
                () -> service.issue(snapshot, UserRole.ADMIN, LoginType.USERNAME_PASSWORD, now.minusSeconds(1)));
    }

    @Test
    void should_issue_token_with_selected_role_and_snapshot_version() throws Exception {
        String secret = "test-secret-that-is-long-enough-1234567890";
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtTokenService service = new JwtTokenService(
                new NimbusJwtEncoder(new ImmutableSecret<>(key)),
                "music-api",
                "music-client",
                86400, java.time.Clock.systemUTC()
        );
        UserAuthorizationSnapshot snapshot = new UserAuthorizationSnapshot(
                42,
                "admin",
                "admin@example.com",
                UserRole.ADMIN,
                7L
        );

        JwtTokenResult result = service.issueLegacyWithoutAuthTime(snapshot, UserRole.ADMIN, LoginType.EMAIL_CODE);

        assertTrue(result.tokenValue().contains("."));
        assertTrue(result.expiresAt().isAfter(Instant.now()));
        assertTrue(java.time.Duration.between(Instant.now(), result.expiresAt()).getSeconds() <= 86400);
        Jwt jwt = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(result.tokenValue());
        assertEquals("42", jwt.getSubject());
        assertEquals("ADMIN", jwt.getClaims().get("role"));
        assertEquals("EMAIL_CODE", jwt.getClaims().get("loginType"));
        assertEquals(7L, jwt.getClaims().get("tokenVersion"));
    }

    @Test
    void should_reject_login_role_above_database_maximum_role() {
        String secret = "test-secret-that-is-long-enough-1234567890";
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtTokenService service = new JwtTokenService(
                new NimbusJwtEncoder(new ImmutableSecret<>(key)),
                "music-api",
                "music-client",
                86400, java.time.Clock.systemUTC()
        );
        UserAuthorizationSnapshot snapshot = new UserAuthorizationSnapshot(
                42,
                "user",
                "user@example.com",
                UserRole.USER,
                7L
        );

        assertThrows(RoleSelectionNotAllowedException.class,
                () -> service.issueLegacyWithoutAuthTime(snapshot, UserRole.ADMIN, LoginType.ROLE_SELECTION));
    }
}
