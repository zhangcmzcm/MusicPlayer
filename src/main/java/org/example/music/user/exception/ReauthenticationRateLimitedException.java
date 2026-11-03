package org.example.music.user.exception;

public class ReauthenticationRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;
    public ReauthenticationRateLimitedException(long retryAfterSeconds) {
        super("再次验证请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
