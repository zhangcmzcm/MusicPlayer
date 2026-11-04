package org.example.music.user.exception;

public class EmailChangeConfirmationRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;
    public EmailChangeConfirmationRateLimitedException(long retryAfterSeconds) {
        super("邮箱变更确认过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
