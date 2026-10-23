package org.example.music.user.service;

public record LoginRateLimitDecision(
        boolean allowed,
        LoginRateLimitScope scope,
        long retryAfterSeconds
) {

    public static LoginRateLimitDecision allowedDecision() {
        return new LoginRateLimitDecision(true, null, 0);
    }

    public static LoginRateLimitDecision limitedDecision(
            LoginRateLimitScope scope,
            long retryAfterSeconds
    ) {
        return new LoginRateLimitDecision(false, scope, retryAfterSeconds);
    }
}