package org.example.music.user.exception;

public class PasswordChangeRateLimitUnavailableException extends RuntimeException {
    public PasswordChangeRateLimitUnavailableException(Throwable cause) {
        super("修改密码配额暂时无法确认，请稍后重试", cause);
    }
}
