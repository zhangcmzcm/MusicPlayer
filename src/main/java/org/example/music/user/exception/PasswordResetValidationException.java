package org.example.music.user.exception;
public class PasswordResetValidationException extends RuntimeException {
 public PasswordResetValidationException() { super("找回密码申请参数不合法"); }
}
