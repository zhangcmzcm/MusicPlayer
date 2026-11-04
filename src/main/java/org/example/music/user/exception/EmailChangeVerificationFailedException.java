package org.example.music.user.exception;

public class EmailChangeVerificationFailedException extends RuntimeException {
    public EmailChangeVerificationFailedException() { super("验证码无效或申请已失效"); }
}
