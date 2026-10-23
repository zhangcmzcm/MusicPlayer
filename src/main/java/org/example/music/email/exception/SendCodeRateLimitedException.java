package org.example.music.email.exception;

import org.example.music.email.service.EmailSendRateLimitScope;

public class SendCodeRateLimitedException extends RuntimeException {

    private final EmailSendRateLimitScope scope;
    private final long retryAfterSeconds;

    public SendCodeRateLimitedException(
            EmailSendRateLimitScope scope,
            long retryAfterSeconds,
            String message
    ) {
        super(message);
        this.scope = scope;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public EmailSendRateLimitScope getScope() {
        return scope;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}