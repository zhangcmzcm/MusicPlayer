package org.example.music.security;

import java.time.Instant;

public record JwtTokenResult(
        String tokenValue,
        Instant expiresAt
) {
}