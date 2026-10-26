package org.example.music.security;

import java.time.Instant;
import java.util.UUID;
import org.example.music.user.domain.UserRole;
import org.example.music.security.UserTokenVersionService;
import org.example.music.user.dto.LoginType;
import org.springframework.beans.factory.annotation.Value;
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
    private final UserTokenVersionService userTokenVersionService;

    public JwtTokenService(
            JwtEncoder jwtEncoder,
            @Value("${app.jwt.issuer}") String issuer,
            @Value("${app.jwt.audience}") String audience,
            @Value("${app.jwt.ttl-seconds}") long ttlSeconds,
            UserTokenVersionService userTokenVersionService
    ) {
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
        this.userTokenVersionService = userTokenVersionService;
    }

    public JwtTokenResult issue(
            Integer userId,
            UserRole role,
            LoginType loginType
    ) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(java.util.List.of(audience))
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", role.name())
                .claim("tokenVersion", userTokenVersionService.current(userId))
                .claim("loginType", loginType.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new JwtTokenResult(token, expiresAt);
    }
}