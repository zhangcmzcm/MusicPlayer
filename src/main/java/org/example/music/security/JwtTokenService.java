package org.example.music.security;

import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.RoleSelectionNotAllowedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;
    private final Clock clock;

    public JwtTokenService(
            JwtEncoder jwtEncoder,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.audience}") String audience,
            @Value("${app.jwt.ttl-seconds}") long ttlSeconds,
            @Qualifier("authenticationClock") Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public JwtTokenResult issue(
            UserAuthorizationSnapshot snapshot,
            UserRole loginRole,
            LoginType loginType,
            Instant authenticatedAt
    ) {
        Instant now = clock.instant();
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        if (authenticatedAt == null || authenticatedAt.isBefore(Instant.EPOCH) || authenticatedAt.isAfter(now)) {
            throw new IllegalArgumentException("Invalid verified authentication time");
        }
        Instant verified = authenticatedAt.truncatedTo(ChronoUnit.SECONDS);
        if (verified.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Invalid verified authentication time");
        }
        return encode(snapshot, loginRole, loginType, issuedAt, verified);
    }

    public JwtTokenResult issueLegacyWithoutAuthTime(
            UserAuthorizationSnapshot snapshot, UserRole loginRole, LoginType loginType) {
        return encode(snapshot, loginRole, loginType, clock.instant().truncatedTo(ChronoUnit.SECONDS), null);
    }

    private JwtTokenResult encode(UserAuthorizationSnapshot snapshot, UserRole loginRole,
            LoginType loginType, Instant issuedAt, Instant authenticatedAt) {
        if (!loginRole.isAllowedBy(snapshot.userRole())) {
            throw new RoleSelectionNotAllowedException();
        }

        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        var builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(String.valueOf(snapshot.userId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", loginRole.name())
                .claim("tokenVersion", snapshot.tokenVersion())
                .claim("loginType", loginType.name());
        if (authenticatedAt != null) {
            builder.claim("auth_time", authenticatedAt.getEpochSecond());
        }
        JwtClaimsSet claims = builder.build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new JwtTokenResult(token, expiresAt);
    }
}
