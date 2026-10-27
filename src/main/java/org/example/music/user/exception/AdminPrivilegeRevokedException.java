package org.example.music.user.exception;

public class AdminPrivilegeRevokedException extends RuntimeException {

    public AdminPrivilegeRevokedException() {
        super("Administrator privilege was revoked before the role change completed");
    }
}
