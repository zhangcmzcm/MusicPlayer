package org.example.music.user.exception;

public class PasswordResetVerificationFailedException extends RuntimeException {
    public PasswordResetVerificationFailedException() { super("验证码或找回申请无效，请检查或重新申请"); }
}
