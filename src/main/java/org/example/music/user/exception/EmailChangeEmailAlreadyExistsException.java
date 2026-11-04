package org.example.music.user.exception;

public class EmailChangeEmailAlreadyExistsException extends RuntimeException {
    public EmailChangeEmailAlreadyExistsException() { super("新邮箱已被使用"); }
}
