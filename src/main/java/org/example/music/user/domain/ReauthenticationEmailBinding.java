package org.example.music.user.domain;

public record ReauthenticationEmailBinding(Integer userId, String email, long tokenVersion) {
    @Override public String toString() {
        return "ReauthenticationEmailBinding[userId=" + userId + ", email=<redacted>, tokenVersion=" + tokenVersion + "]";
    }
}
