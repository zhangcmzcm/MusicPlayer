package org.example.music.user.exception;

public class ReauthenticationFailedException extends RuntimeException {
    public ReauthenticationFailedException() { super("身份验证失败，请检查凭据或重新申请验证码"); }
}
