package org.example.music.user.exception;

public class UserProfileStoreUnavailableException extends RuntimeException {
    public UserProfileStoreUnavailableException(Throwable cause) {
        super("User profile store is unavailable", cause);
    }
}
