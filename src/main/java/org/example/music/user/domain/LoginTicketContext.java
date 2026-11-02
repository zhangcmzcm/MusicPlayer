package org.example.music.user.domain;

import org.example.music.user.dto.LoginType;

public record LoginTicketContext(
        Integer userId,
        UserRole maximumRole,
        LoginType loginType,
        long tokenVersion,
        java.time.Instant authenticatedAt
) {
    public LoginTicketContext {
        java.util.Objects.requireNonNull(authenticatedAt, "Verified authentication time is required");
    }
}
