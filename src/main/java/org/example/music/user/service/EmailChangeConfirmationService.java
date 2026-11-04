package org.example.music.user.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.function.Supplier;
import org.example.music.security.*;
import org.example.music.user.domain.EmailChangeConfirmationRules;
import org.example.music.user.dto.EmailChangeConfirmationRequest;
import org.example.music.user.exception.*;
import org.example.music.user.repository.EmailChangeConfirmationStore;
import org.springframework.stereotype.Service;

@Service
public class EmailChangeConfirmationService {
    private final EmailChangeRecentAuthenticationGuard guard;
    private final EmailChangeConfirmationStore store;
    private final EmailChangeConfirmationSnapshotReader reader;
    private final EmailChangeConfirmationRules rules;
    private final PasswordHasher hasher;
    private final EmailChangeConfirmationTransactionService updater;

    public EmailChangeConfirmationService(EmailChangeRecentAuthenticationGuard guard, EmailChangeConfirmationStore store,
            EmailChangeConfirmationSnapshotReader reader, EmailChangeConfirmationRules rules, PasswordHasher hasher,
            EmailChangeConfirmationTransactionService updater) {
        this.guard=guard; this.store=store; this.reader=reader; this.rules=rules; this.hasher=hasher; this.updater=updater;
    }

    public void confirm(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, Supplier<EmailChangeConfirmationRequest> input) {
        guard.requireRecent(actor, session);
        var request = input.get();
        try {
            store.admit(actor.userId());
            var challenge = store.read(actor.userId(), digest(request.challengeId()));
            var account = reader.load(actor, session);
            rules.requireBinding(challenge, account, session.tokenVersion());
            if (!hasher.matches(request.code(), challenge.codeHash())) {
                store.recordFailure(challenge);
                throw new EmailChangeVerificationFailedException();
            }
            var prepared = reader.prepare(actor, session, challenge);
            // 只有明确的消费成功才能进入SQL；消费或提交失败都不恢复凭据、不重试。
            store.consume(challenge);
            updater.update(actor, session, prepared, challenge.binding().newEmail());
        } catch (EmailChangeVerificationFailedException | EmailChangeConfirmationRateLimitedException
                | EmailChangeConfirmationUnavailableException | EmailChangeConflictException
                | EmailChangeEmailAlreadyExistsException | CurrentUserRevokedException | InvalidTokenException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Email change confirmation failed unexpectedly", EmailChangeDiagnostics.sanitized(failure));
        }
    }

    private static String digest(String value) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest unavailable"); }
    }
}
