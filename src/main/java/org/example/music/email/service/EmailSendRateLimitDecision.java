package org.example.music.email.service;

public record EmailSendRateLimitDecision(
        boolean allowed,
        EmailSendRateLimitScope scope,
        long retryAfterSeconds
) {

    public static EmailSendRateLimitDecision allowedDecision() {
        return new EmailSendRateLimitDecision(true, null, 0);
    }

    public static EmailSendRateLimitDecision limitedDecision(
            EmailSendRateLimitScope scope,
            long retryAfterSeconds
    ) {
        return new EmailSendRateLimitDecision(false, scope, retryAfterSeconds);
    }
}