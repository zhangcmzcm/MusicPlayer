package org.example.music.user.exception;

public class UserProfileUpdateUnavailableException extends RuntimeException {
    public UserProfileUpdateUnavailableException(Throwable cause) {
        super("User profile update is unavailable", cause);
    }
}
