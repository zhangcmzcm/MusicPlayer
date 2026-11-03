package org.example.music.user.exception;

public class ReauthenticationValidationException extends RuntimeException {
    public ReauthenticationValidationException() { super("再次验证请求格式不正确"); }
}
