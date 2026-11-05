package org.example.music.user.exception;
public class PasswordResetUnsupportedMediaTypeException extends RuntimeException {
 public PasswordResetUnsupportedMediaTypeException() { super("找回密码申请需要UTF-8 JSON正文"); }
}
