package org.example.music.user.exception;

public class ReauthenticationEmailRequestUnavailableException extends RuntimeException {
    public ReauthenticationEmailRequestUnavailableException(Throwable cause) { super("身份验证邮件请求暂不可用，请稍后重试", cause); }
}
