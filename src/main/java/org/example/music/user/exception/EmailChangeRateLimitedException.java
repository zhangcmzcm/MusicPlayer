package org.example.music.user.exception;

public class EmailChangeRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;
    public EmailChangeRateLimitedException(long retryAfterSeconds) {
        super("邮箱变更邮件请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
