package org.example.music.user.exception;

public class PasswordChangeStoreUnavailableException extends RuntimeException {
    public PasswordChangeStoreUnavailableException(Throwable cause) {
        super("密码暂时无法修改，请稍后重试", cause);
    }
}
