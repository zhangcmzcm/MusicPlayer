package org.example.music.user.exception;

public class ReauthenticationEmailSendFailedException extends RuntimeException {
    public ReauthenticationEmailSendFailedException(Throwable cause) { super("身份验证邮件暂时无法发送", cause); }
}
