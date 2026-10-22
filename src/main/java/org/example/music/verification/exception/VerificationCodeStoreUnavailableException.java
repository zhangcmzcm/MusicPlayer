package org.example.music.verification.exception;

public class VerificationCodeStoreUnavailableException extends RuntimeException {

    public VerificationCodeStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}