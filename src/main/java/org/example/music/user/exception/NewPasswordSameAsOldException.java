package org.example.music.user.exception;

public class NewPasswordSameAsOldException extends RuntimeException {
    public NewPasswordSameAsOldException() {
        super("新密码不能与当前密码相同");
    }
}
