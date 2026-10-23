package org.example.music.user.exception;

public class AuthStateStoreUnavailableException extends RuntimeException {

    public AuthStateStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}