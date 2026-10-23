package org.example.music.verification.service;

import org.example.music.verification.domain.VerificationCodePurpose;

public record VerifiedEmailCode(
        String email,
        String version,
        VerificationCodePurpose purpose
) {

    public VerifiedEmailCode(String email, String version) {
        this(email, version, VerificationCodePurpose.REGISTER);
    }
}