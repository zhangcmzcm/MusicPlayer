package org.example.music.user.exception;

public class EmailChangeUnsupportedMediaTypeException extends RuntimeException {
    public EmailChangeUnsupportedMediaTypeException() { super("邮箱变更请求需要UTF-8 JSON正文"); }
}
