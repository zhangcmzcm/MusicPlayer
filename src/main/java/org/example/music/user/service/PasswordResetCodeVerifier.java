package org.example.music.user.service;

import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetCodeVerifier {
    private final PasswordHasher hasher;
    private final String dummyHash;
    public PasswordResetCodeVerifier(PasswordHasher hasher, PasswordResetConfirmationRules rules) {
        this.hasher = hasher;
        try {
            this.dummyHash = hasher.hash(PasswordResetIdentifiers.generate());
            rules.requirePasswordHash(dummyHash);
        } catch (RuntimeException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
    }
    public boolean matches(String code, String hash) {
        try { return hasher.matches(code, hash); }
        catch (RuntimeException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
    }
    public void reject(String code) {
        // 结果刻意忽略。伪比较只补足普通失败的成本，永远不授予兑换资格。
        matches(code, dummyHash);
        throw new PasswordResetVerificationFailedException();
    }
}
