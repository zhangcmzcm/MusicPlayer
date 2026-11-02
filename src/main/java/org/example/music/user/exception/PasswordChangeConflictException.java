package org.example.music.user.exception;

public class PasswordChangeConflictException extends RuntimeException {
    public PasswordChangeConflictException() {
        super("账户状态已变化，请重新登录后操作");
    }
}
