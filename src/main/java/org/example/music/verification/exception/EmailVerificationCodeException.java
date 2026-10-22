package org.example.music.verification.exception;

public class EmailVerificationCodeException extends RuntimeException {

    public enum Failure {
        INVALID,
        EXPIRED,
        ALREADY_USED,
        ATTEMPTS_EXCEEDED
    }

    private final Failure failure;
    private final Long verificationCodeId;

    public EmailVerificationCodeException(Failure failure, Long verificationCodeId, String message) {
        super(message);
        this.failure = failure;
        this.verificationCodeId = verificationCodeId;
    }

    public Failure getFailure() {
        return failure;
    }

    public Long getVerificationCodeId() {
        return verificationCodeId;
    }
}
