package org.example.music.user.exception;

public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Token required claims are missing or invalid");
    }
}
