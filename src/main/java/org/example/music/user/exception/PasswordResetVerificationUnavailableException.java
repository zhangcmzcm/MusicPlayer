package org.example.music.user.exception;

public class PasswordResetVerificationUnavailableException extends RuntimeException {
    public PasswordResetVerificationUnavailableException(Throwable cause) {
        super("找回密码验证暂时不可用", PasswordResetDiagnostics.sanitized(cause));
    }
}
