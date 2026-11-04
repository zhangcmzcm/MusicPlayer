package org.example.music.user.exception;

public class EmailChangeConflictException extends RuntimeException {
    public EmailChangeConflictException() { super("邮箱状态已变化，请重新申请"); }
}
