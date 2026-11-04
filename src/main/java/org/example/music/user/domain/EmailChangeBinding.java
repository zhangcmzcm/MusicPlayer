package org.example.music.user.domain;

public record EmailChangeBinding(Integer userId, String currentEmail, String newEmail, long tokenVersion) {
    @Override public String toString() {
        return "EmailChangeBinding[userId=" + userId + ", currentEmail=<redacted>, newEmail=<redacted>, tokenVersion=" + tokenVersion + "]";
    }
}
