package org.example.music.user.exception;
public class PasswordResetRequestRateLimitedException extends RuntimeException {
 private final long retryAfter;
 public PasswordResetRequestRateLimitedException(long seconds){super("找回密码申请过于频繁");retryAfter=seconds;}
 public long retryAfterSeconds(){return retryAfter;}
}
