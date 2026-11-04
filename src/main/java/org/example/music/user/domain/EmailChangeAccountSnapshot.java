package org.example.music.user.domain;

public record EmailChangeAccountSnapshot(Integer userId, String email, UserRole userRole, long tokenVersion) {
    @Override public String toString() {
        return "EmailChangeAccountSnapshot[userId=" + userId + ", email=<redacted>, userRole=" + userRole + ", tokenVersion=" + tokenVersion + "]";
    }
}
