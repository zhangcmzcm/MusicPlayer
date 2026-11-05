package org.example.music.user.exception;
public class PasswordResetRequestUnavailableException extends RuntimeException {
 public PasswordResetRequestUnavailableException(Throwable cause){super("找回密码申请暂时不可用",PasswordResetDiagnostics.sanitized(cause));}
}
