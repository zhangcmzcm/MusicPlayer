package org.example.music.user.exception;

public class UserAlreadyExistsException extends RuntimeException {

    public static final String USERNAME_ALREADY_EXISTS = "USERNAME_ALREADY_EXISTS";
    public static final String EMAIL_ALREADY_EXISTS = "EMAIL_ALREADY_EXISTS";
    public static final String USER_ALREADY_EXISTS = "USER_ALREADY_EXISTS";

    private final String errorCode;

    public UserAlreadyExistsException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

}