package org.example.music.user.exception;

public class RoleSelectionNotAllowedException extends RuntimeException {

    public RoleSelectionNotAllowedException() {
        super("Selected login role is not allowed");
    }
}