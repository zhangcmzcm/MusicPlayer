package org.example.music.security;

import java.time.Instant;
import java.util.Optional;
import org.springframework.security.core.Authentication;

public record AuthenticatedSessionContext(long tokenVersion, Instant authenticatedAt) {
    public static Optional<AuthenticatedSessionContext> from(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal
                && authentication.getDetails() instanceof AuthenticatedSessionContext context) {
            return Optional.of(context);
        }
        return Optional.empty();
    }
}
