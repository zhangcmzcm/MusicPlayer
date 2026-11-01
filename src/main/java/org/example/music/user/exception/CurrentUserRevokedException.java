package org.example.music.user.exception;

public class CurrentUserRevokedException extends RuntimeException {
    public CurrentUserRevokedException() {
        super("Current user account no longer exists");
    }
}
