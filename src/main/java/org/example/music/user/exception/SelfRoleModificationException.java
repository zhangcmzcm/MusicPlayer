package org.example.music.user.exception;

public class SelfRoleModificationException extends RuntimeException {

    public SelfRoleModificationException() {
        super("Administrators cannot modify their own role");
    }
}