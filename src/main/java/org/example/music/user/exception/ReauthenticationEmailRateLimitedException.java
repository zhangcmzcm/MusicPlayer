package org.example.music.user.exception;

public class ReauthenticationEmailRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;
    public ReauthenticationEmailRateLimitedException(long retryAfterSeconds) {
        super("身份验证邮件请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
