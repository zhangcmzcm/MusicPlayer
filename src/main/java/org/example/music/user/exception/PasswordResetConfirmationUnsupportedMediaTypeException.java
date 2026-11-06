package org.example.music.user.exception;

public class PasswordResetConfirmationUnsupportedMediaTypeException extends RuntimeException {
    public PasswordResetConfirmationUnsupportedMediaTypeException() { super("需要UTF-8 application/json"); }
}
