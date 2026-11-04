package org.example.music.user.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.function.Supplier;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.security.*;
import org.example.music.user.domain.EmailChangeBinding;
import org.example.music.user.dto.*;
import org.example.music.user.email.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.EmailChangeRequestStore;
import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class EmailChangeRequestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailChangeRequestService.class);
    private final SecureRandom random = new SecureRandom();
    private final EmailChangeRecentAuthenticationGuard guard;
    private final EmailChangeSnapshotReader snapshots;
    private final EmailChangeRequestStore store;
    private final SecureVerificationCodeGenerator codes;
    private final PasswordHasher hasher;
    private final BoundedEmailChangeEmailDelivery delivery;

    public EmailChangeRequestService(EmailChangeRecentAuthenticationGuard guard, EmailChangeSnapshotReader snapshots,
            EmailChangeRequestStore store, SecureVerificationCodeGenerator codes, PasswordHasher hasher,
            BoundedEmailChangeEmailDelivery delivery) {
        this.guard = guard;
        this.snapshots = snapshots;
        this.store = store;
        this.codes = codes;
        this.hasher = hasher;
        this.delivery = delivery;
    }

    public EmailChangeRequestResponse request(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session,
            Supplier<EmailChangeRequest> input, String clientIp) {
        // 延迟读取使普通认证和一次近期门禁先于任何正文解析，后续耗时不重算资格。
        guard.requireRecent(actor, session);
        var request = input.get();
        EmailChangeBinding binding = null;
        String digest = null;
        boolean admissionAttempted = false;
        boolean activated = false;
        try {
            binding = snapshots.loadBinding(actor, session, request.newEmail());
            snapshots.requireTargetAvailable(binding);
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String challengeId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            digest = digest(challengeId);
            admissionAttempted = true;
            var reservation = store.reserve(binding, digest, digest(clientIp));
            String code = asciiCode(codes.generate());
            store.prepare(binding, reservation, hasher.hash(code));
            delivery.send(new EmailChangeMessage(binding.newEmail(), code, reservation.expiresAt()));
            var current = snapshots.loadBinding(actor, session, request.newEmail());
            if (!binding.equals(current)) throw new EmailChangeRequestConflictException();
            snapshots.requireTargetAvailable(current);
            store.activate(binding, reservation);
            activated = true;
            return new EmailChangeRequestResponse(challengeId, reservation.expiresAt());
        } catch (CurrentUserRevokedException | InvalidTokenException | EmailUnchangedException
                | EmailChangeEmailAlreadyExistsException | EmailChangeRequestConflictException
                | EmailChangeRateLimitedException | EmailChangeRequestUnavailableException | EmailChangeSendFailedException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Email change request failed unexpectedly", EmailChangeDiagnostics.sanitized(failure));
        } finally {
            // 仅清理本次pending；激活回包未知时不恢复旧ACTIVE或返还额度。
            if (admissionAttempted && !activated) {
                try { store.cancel(binding, digest); }
                catch (RuntimeException failure) {
                    LOGGER.warn("Email change cleanup unconfirmed, traceId={}, exceptionType={}", MDC.get("traceId"),
                            failure.getClass().getSimpleName(), EmailChangeDiagnostics.sanitized(failure));
                }
            }
        }
    }

    private static String digest(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required digest unavailable"); }
    }

    private static String asciiCode(String generated) {
        if (generated == null || generated.length() != 6) throw new IllegalStateException("Invalid verification code generator output");
        var result = new StringBuilder(6);
        for (int i = 0; i < generated.length(); i++) {
            int digit = Character.digit(generated.charAt(i), 10);
            if (digit < 0) throw new IllegalStateException("Invalid verification code generator output");
            result.append((char) ('0' + digit));
        }
        return result.toString();
    }
}
