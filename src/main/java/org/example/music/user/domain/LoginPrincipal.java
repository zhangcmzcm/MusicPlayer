package org.example.music.user.domain;

public record LoginPrincipal(
        Integer id,
        String username,
        String email,
        UserRole maximumRole,
        long tokenVersion,
        java.time.Instant authenticatedAt
) {
    public LoginPrincipal {
        java.util.Objects.requireNonNull(authenticatedAt, "Verified authentication time is required");
    }
}
