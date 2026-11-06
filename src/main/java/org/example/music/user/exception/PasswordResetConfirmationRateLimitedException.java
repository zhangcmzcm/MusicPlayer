package org.example.music.user.exception;

public class PasswordResetConfirmationRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;
    public PasswordResetConfirmationRateLimitedException(long retryAfterSeconds) {
        super("请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
