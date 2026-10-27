package org.example.music.user.exception;

public class LastAdminCannotBeDemotedException extends RuntimeException {

    public LastAdminCannotBeDemotedException() {
        super("The last administrator cannot be demoted");
    }
}
