package org.example.music.user.exception;

public class EmailUnchangedException extends RuntimeException {
    public EmailUnchangedException() { super("新邮箱与当前邮箱相同"); }
}
