package org.example.music.user.email;

import java.time.Instant;

public record EmailChangeMessage(String recipient, String code, Instant expiresAt) {
    @Override public String toString() { return "EmailChangeMessage[recipient=<redacted>, code=<redacted>, expiresAt=" + expiresAt + "]"; }
}
