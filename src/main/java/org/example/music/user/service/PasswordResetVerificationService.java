package org.example.music.user.service;

import org.example.music.user.domain.PasswordResetIdentifiers;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetVerificationService {
    private final PasswordResetConfirmationStore codes;
    private final PasswordResetCodeVerifier verifier;
    private final PasswordResetConfirmationSnapshotReader reader;
    private final PasswordResetTicketStore tickets;

    public PasswordResetVerificationService(PasswordResetConfirmationStore codes, PasswordResetCodeVerifier verifier,
            PasswordResetConfirmationSnapshotReader reader, PasswordResetTicketStore tickets) {
        this.codes = codes;
        this.verifier = verifier;
        this.reader = reader;
        this.tickets = tickets;
    }
    public PasswordResetVerificationResponse verify(PasswordResetVerificationRequest request, String clientIp) {
        try {
            codes.admitIp(PasswordResetIdentifiers.digest(clientIp));
            var found = codes.read(PasswordResetIdentifiers.digest(request.challengeId()));
            if (found.isEmpty()) verifier.reject(request.code());
            var challenge = found.orElseThrow(PasswordResetVerificationFailedException::new);
            if (!codes.admitAccount(challenge.account().userId()) || !reader.isCurrent(challenge)) verifier.reject(request.code());
            if (!verifier.matches(request.code(),challenge.codeHash())) {
                codes.recordFailure(challenge);
                throw new PasswordResetVerificationFailedException();
            }
            if (!reader.isCurrent(challenge)) throw new PasswordResetVerificationFailedException();
            var previous = tickets.readCurrent(challenge.account().userId());
            var secret = PasswordResetIdentifiers.generate();
            var period = tickets.exchange(challenge,previous,PasswordResetIdentifiers.digest(secret))
                    .orElseThrow(PasswordResetVerificationFailedException::new);
            return new PasswordResetVerificationResponse(secret,period.expiresAt());
        } catch (PasswordResetConfirmationRateLimitedException failure) {
            throw new PasswordResetVerificationRateLimitedException(failure.retryAfterSeconds());
        } catch (PasswordResetConfirmationUnavailableException failure) {
            throw new PasswordResetVerificationUnavailableException(failure);
        } catch (PasswordResetVerificationRateLimitedException | PasswordResetVerificationUnavailableException
                | PasswordResetVerificationFailedException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Password reset verification failed unexpectedly",PasswordResetDiagnostics.sanitized(failure));
        }
    }
}
