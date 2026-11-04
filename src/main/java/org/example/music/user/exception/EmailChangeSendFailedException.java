package org.example.music.user.exception;

public class EmailChangeSendFailedException extends RuntimeException {
    public EmailChangeSendFailedException(Throwable cause) {
        super("新邮箱验证码暂时无法发送", EmailChangeDiagnostics.sanitized(cause));
    }
}
