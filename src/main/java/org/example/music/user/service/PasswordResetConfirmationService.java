package org.example.music.user.service;

import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.dto.PasswordResetConfirmationRequest;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetConfirmationStore;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetConfirmationService {
    private final PasswordResetConfirmationStore store;
    private final PasswordResetConfirmationSnapshotReader reader;
    private final PasswordResetConfirmationRules rules;
    private final PasswordResetCodeVerifier verifier;
    private final PasswordHasher hasher;
    private final PasswordResetConfirmationTransactionService updater;

    public PasswordResetConfirmationService(PasswordResetConfirmationStore store, PasswordResetConfirmationSnapshotReader reader,
            PasswordResetConfirmationRules rules, PasswordResetCodeVerifier verifier, PasswordHasher hasher,
            PasswordResetConfirmationTransactionService updater) {
        this.store = store; this.reader = reader; this.rules = rules;
        this.verifier = verifier; this.hasher = hasher; this.updater = updater;
    }
    public void reset(PasswordResetConfirmationRequest request, String clientIp) {
        try {
            store.admitIp(PasswordResetIdentifiers.digest(clientIp));
            var found = store.read(PasswordResetIdentifiers.digest(request.challengeId()));
            if (found.isEmpty()) verifier.reject(request.code());
            var challenge = found.orElseThrow(PasswordResetVerificationFailedException::new);
            if (!store.admitAccount(challenge.account().userId()) || !reader.isCurrent(challenge)) verifier.reject(request.code());
            if (!verifier.matches(request.code(),challenge.codeHash())) {
                store.recordFailure(challenge);
                throw new PasswordResetVerificationFailedException();
            }
            var expected = reader.prepare(challenge).orElseThrow(PasswordResetVerificationFailedException::new);
            String newHash;
            try {
                if (hasher.matches(request.newPassword(),expected.passwordHash())) throw new NewPasswordSameAsOldException();
                if (expected.tokenVersion() == Long.MAX_VALUE) throw new PasswordResetConfirmationUnavailableException(null);
                newHash = hasher.hash(request.newPassword());
                rules.requirePasswordHash(newHash);
            } catch (NewPasswordSameAsOldException | PasswordResetConfirmationUnavailableException known) { throw known; }
            catch (RuntimeException failure) { throw new PasswordResetConfirmationUnavailableException(failure); }
            // 只有明确消费成功才进入短写事务。任何未知结果均不恢复、不重试。
            if (!store.consume(challenge)) throw new PasswordResetVerificationFailedException();
            updater.update(expected,newHash);
        } catch (PasswordResetVerificationFailedException | PasswordResetConfirmationRateLimitedException
                | PasswordResetConfirmationUnavailableException | NewPasswordSameAsOldException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Password reset confirmation failed unexpectedly", PasswordResetDiagnostics.sanitized(failure));
        }
    }
}
