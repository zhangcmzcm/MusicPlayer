package org.example.music.user.domain;

public record PasswordResetChallengeSnapshot(String digest, String emailDigest, String email,
        PasswordResetAdmission admission, PasswordResetAccountSnapshot account, String codeHash, int attempts) {
    @Override public String toString() { return "PasswordResetChallengeSnapshot[redacted]"; }
}
