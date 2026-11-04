package org.example.music.user.exception;

public class EmailChangeRequestConflictException extends RuntimeException {
    public EmailChangeRequestConflictException() { super("验证请求已过期或账户状态已变化，请重新申请"); }
}
