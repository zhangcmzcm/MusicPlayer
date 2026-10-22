package org.example.music.verification.exception;

public class EmailVerificationCodeException extends RuntimeException {

    public enum Failure {
        INVALID,
        ALREADY_USED,
        ATTEMPTS_EXCEEDED
    }

    private final Failure failure;

    public EmailVerificationCodeException(Failure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public Failure getFailure() {
        return failure;
    }
}