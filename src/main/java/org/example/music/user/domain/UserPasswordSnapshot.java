package org.example.music.user.domain;

public record UserPasswordSnapshot(Integer id, String passwordHash, long tokenVersion) {
    @Override
    public String toString() {
        return "UserPasswordSnapshot[id=" + id + ", passwordHash=<redacted>, tokenVersion=" + tokenVersion + "]";
    }
}
