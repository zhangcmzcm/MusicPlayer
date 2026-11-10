package org.example.music.user.service;

import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.dto.PasswordResetConfirmationRequest;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetCompletionQuotaStore;
import org.example.music.user.repository.PasswordResetTicketStore;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetConfirmationService {
    private final PasswordResetCompletionQuotaStore quota;
    private final PasswordResetTicketStore tickets;
    private final PasswordResetConfirmationSnapshotReader reader;
    private final PasswordResetConfirmationRules rules;
    private final PasswordHasher hasher;
    private final PasswordResetConfirmationTransactionService updater;

    public PasswordResetConfirmationService(PasswordResetCompletionQuotaStore quota, PasswordResetTicketStore tickets,
            PasswordResetConfirmationSnapshotReader reader, PasswordResetConfirmationRules rules, PasswordHasher hasher,
            PasswordResetConfirmationTransactionService updater) {
        this.quota = quota;
        this.tickets = tickets;
        this.reader = reader;
        this.rules = rules;
        this.hasher = hasher;
        this.updater = updater;
    }

    public void reset(PasswordResetConfirmationRequest request, String clientIp) {
        try {
            quota.admitIp(PasswordResetIdentifiers.digest(clientIp));
            var ticket = tickets.readByDigest(PasswordResetIdentifiers.digest(request.resetTicket()))
                    .orElseThrow(PasswordResetVerificationFailedException::new);
            if (!quota.admitAccount(ticket.account().userId())) throw new PasswordResetVerificationFailedException();
            var expected = reader.prepareTicket(ticket).orElseThrow(PasswordResetVerificationFailedException::new);
            String newHash;
            try {
                if (hasher.matches(request.newPassword(), expected.passwordHash())) throw new NewPasswordSameAsOldException();
                if (expected.tokenVersion() == Long.MAX_VALUE) throw new PasswordResetConfirmationUnavailableException(null);
                newHash = hasher.hash(request.newPassword());
                rules.requirePasswordHash(newHash);
            } catch (NewPasswordSameAsOldException | PasswordResetConfirmationUnavailableException known) {
                throw known;
            } catch (RuntimeException failure) {
                throw new PasswordResetConfirmationUnavailableException(failure);
            }
            // 消费结果未知时结束本次操作，只有明确成功才能开始数据库写事务。
            if (!tickets.consume(ticket)) throw new PasswordResetVerificationFailedException();
            updater.update(expected, newHash);
        } catch (PasswordResetVerificationUnavailableException failure) {
            throw new PasswordResetConfirmationUnavailableException(failure);
        } catch (PasswordResetVerificationFailedException | PasswordResetConfirmationRateLimitedException
                | PasswordResetConfirmationUnavailableException | NewPasswordSameAsOldException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Password reset confirmation failed unexpectedly", PasswordResetDiagnostics.sanitized(failure));
        }
    }
}
