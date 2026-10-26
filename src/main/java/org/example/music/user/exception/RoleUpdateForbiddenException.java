package org.example.music.user.exception;

public class RoleUpdateForbiddenException extends RuntimeException {

    public RoleUpdateForbiddenException() {
        super("Only administrators can modify user roles");
    }
}