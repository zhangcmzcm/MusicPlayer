package org.example.music.verification.service;

public record VerifiedEmailCode(
        String email,
        String version
) {
}