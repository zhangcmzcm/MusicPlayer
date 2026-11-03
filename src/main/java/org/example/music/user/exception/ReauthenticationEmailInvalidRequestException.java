package org.example.music.user.exception;

public class ReauthenticationEmailInvalidRequestException extends RuntimeException {
    public ReauthenticationEmailInvalidRequestException() { super("请求不允许包含正文"); }
}
