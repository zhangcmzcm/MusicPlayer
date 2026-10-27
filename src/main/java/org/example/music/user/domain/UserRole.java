package org.example.music.user.domain;

public enum UserRole {

    USER,
    ADMIN;

    public boolean isAllowedBy(UserRole maximumRole) {
        return this == USER || maximumRole == ADMIN;
    }

}