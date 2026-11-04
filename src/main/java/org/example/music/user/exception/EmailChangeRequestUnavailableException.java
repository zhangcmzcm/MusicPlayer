package org.example.music.user.exception;

public class EmailChangeRequestUnavailableException extends RuntimeException {
    public EmailChangeRequestUnavailableException(Throwable cause) { super("邮箱变更邮件请求暂不可用，请稍后重试", EmailChangeDiagnostics.sanitized(cause)); }
}
