package org.example.music.user.exception;

public class ReauthenticationUnavailableException extends RuntimeException {
    public ReauthenticationUnavailableException(Throwable cause) { super("再次验证暂不可用，请稍后重试", cause); }
}
