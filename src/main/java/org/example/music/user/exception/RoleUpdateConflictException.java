package org.example.music.user.exception;

public class RoleUpdateConflictException extends RuntimeException {

    public RoleUpdateConflictException() {
        super("User role changed concurrently");
    }
}
