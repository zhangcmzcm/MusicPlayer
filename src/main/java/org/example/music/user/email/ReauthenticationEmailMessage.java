package org.example.music.user.email;

import java.time.Instant;

public record ReauthenticationEmailMessage(String recipient, String code, Instant expiresAt) {
    @Override public String toString() { return "ReauthenticationEmailMessage[recipient=<redacted>, code=<redacted>, expiresAt=" + expiresAt + "]"; }
}
