package org.example.music.user.exception;

public class PasswordResetConfirmationUnavailableException extends RuntimeException {
    public PasswordResetConfirmationUnavailableException(Throwable cause) {
        super("密码重置暂时不可用", PasswordResetDiagnostics.sanitized(cause));
    }
}
