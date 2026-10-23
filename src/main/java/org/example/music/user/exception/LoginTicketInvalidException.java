package org.example.music.user.exception;

public class LoginTicketInvalidException extends RuntimeException {

    public LoginTicketInvalidException() {
        super("Login ticket is invalid, expired, or already used");
    }
}