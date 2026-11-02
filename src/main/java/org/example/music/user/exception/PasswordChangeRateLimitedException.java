package org.example.music.user.exception;

public class PasswordChangeRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public PasswordChangeRateLimitedException(long retryAfterSeconds) {
        super("修改密码请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
