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

    @Test
    void should_issue_token_with_selected_role_and_snapshot_version() throws Exception {
        String secret = "test-secret-that-is-long-enough-1234567890";
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtTokenService service = new JwtTokenService(
                new NimbusJwtEncoder(new ImmutableSecret<>(key)),
                "music-api",
                "music-client",
                86400
        );
        UserAuthorizationSnapshot snapshot = new UserAuthorizationSnapshot(
                42,
                "admin",
                "admin@example.com",
                UserRole.ADMIN,
                7L
        );

        JwtTokenResult result = service.issue(snapshot, UserRole.ADMIN, LoginType.EMAIL_CODE);

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
                86400
        );
        UserAuthorizationSnapshot snapshot = new UserAuthorizationSnapshot(
                42,
                "user",
                "user@example.com",
                UserRole.USER,
                7L
        );

        assertThrows(RoleSelectionNotAllowedException.class,
                () -> service.issue(snapshot, UserRole.ADMIN, LoginType.ROLE_SELECTION));
    }
}
