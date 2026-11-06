package org.example.music.user.exception;

public class PasswordResetConfirmationValidationException extends RuntimeException {
    public PasswordResetConfirmationValidationException() { super("请求字段或格式无效"); }
}
