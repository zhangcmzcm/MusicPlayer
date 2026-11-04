package org.example.music.user.exception;

public class EmailChangeValidationException extends RuntimeException {
    public EmailChangeValidationException() { super("邮箱变更请求格式不正确"); }
}
