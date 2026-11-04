package org.example.music.user.exception;

public class ReauthenticationRequiredException extends RuntimeException {
    public ReauthenticationRequiredException() { super("请再次验证本人身份后重试"); }
}
