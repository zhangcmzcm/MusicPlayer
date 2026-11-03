package org.example.music.user.exception;

public class CurrentEmailNotBoundException extends RuntimeException {
    public CurrentEmailNotBoundException() { super("当前账户没有绑定邮箱"); }
}
