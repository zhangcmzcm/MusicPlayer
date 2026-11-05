package org.example.music.user.exception;
public class PasswordResetSendFailedException extends RuntimeException {
 public PasswordResetSendFailedException(Throwable cause){super("找回密码邮件发送未确认",PasswordResetDiagnostics.sanitized(cause));}
}
