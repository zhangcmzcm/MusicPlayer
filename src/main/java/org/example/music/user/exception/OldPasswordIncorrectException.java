package org.example.music.user.exception;

public class OldPasswordIncorrectException extends RuntimeException {
    public OldPasswordIncorrectException() {
        super("原密码不正确");
    }
}
