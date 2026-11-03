package org.example.music.user.service;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.security.AuthenticatedSessionContext;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.ReauthenticationEmailBinding;
import org.example.music.user.dto.ReauthenticationEmailRequestResponse;
import org.example.music.user.email.BoundedReauthenticationEmailDelivery;
import org.example.music.user.email.ReauthenticationEmailMessage;
import org.example.music.user.exception.*;
import org.example.music.user.repository.ReauthenticationEmailRequestStore;
import org.example.music.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class ReauthenticationEmailRequestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReauthenticationEmailRequestService.class);
    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final ReauthenticationEmailRequestStore store;
    private final SecureVerificationCodeGenerator codes;
    private final PasswordHasher hasher;
    private final BoundedReauthenticationEmailDelivery delivery;

    public ReauthenticationEmailRequestService(UserRepository users, ReauthenticationEmailRequestStore store,
            SecureVerificationCodeGenerator codes, PasswordHasher hasher, BoundedReauthenticationEmailDelivery delivery) {
        this.users = users;
        this.store = store;
        this.codes = codes;
        this.hasher = hasher;
        this.delivery = delivery;
    }

    public ReauthenticationEmailRequestResponse request(AuthenticatedUserPrincipal actor,
            AuthenticatedSessionContext session, String clientIp) {
        if (actor == null || actor.userId() == null || actor.loginRole() == null || session == null || session.tokenVersion() < 0) {
            throw new InvalidTokenException();
        }
        ReauthenticationEmailBinding binding = null;
        String digest = null;
        boolean admissionAttempted = false;
        boolean activated = false;
        try {
            binding = binding(actor, session, false);
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String challengeId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            digest = digest(challengeId);
            admissionAttempted = true;
            var reservation = store.reserve(binding, digest, digest(clientIp));
            String code = asciiCode(codes.generate());
            store.prepare(binding, reservation, hasher.hash(code));
            delivery.send(new ReauthenticationEmailMessage(binding.email(), code, reservation.expiresAt()));
            var current = binding(actor, session, true);
            if (!binding.equals(current)) throw new ReauthenticationEmailRequestConflictException();
            // 邮件线程没有存储能力；仅请求线程在绑定复核后才能激活。
            store.activate(binding, reservation);
            activated = true;
            return new ReauthenticationEmailRequestResponse(challengeId, reservation.expiresAt());
        } catch (InvalidTokenException | CurrentUserRevokedException | CurrentEmailNotBoundException
                | ReauthenticationEmailRequestConflictException | ReauthenticationEmailRateLimitedException
                | ReauthenticationEmailRequestUnavailableException | ReauthenticationEmailSendFailedException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Reauthentication email request failed unexpectedly",
                    ReauthenticationEmailDiagnostics.sanitized(failure));
        } finally {
            // 即使准入或激活回包丢失，也只按本次归属清理pending，不补偿额度/active。
            if (admissionAttempted && !activated) {
                try { store.cancel(binding, digest); }
                catch (RuntimeException failure) {
                    LOGGER.warn("Reauthentication email cleanup unconfirmed, traceId={}, exceptionType={}",
                            MDC.get("traceId"), failure.getClass().getSimpleName(), ReauthenticationEmailDiagnostics.sanitized(failure));
                }
            }
        }
    }

    private ReauthenticationEmailBinding binding(AuthenticatedUserPrincipal actor, AuthenticatedSessionContext session, boolean afterSend) {
        try {
            var snapshot = users.findAuthorizationSnapshot(actor.userId()).orElseThrow(CurrentUserRevokedException::new);
            if (snapshot.tokenVersion() != session.tokenVersion() || !actor.loginRole().isAllowedBy(snapshot.userRole())) {
                throw new CurrentUserRevokedException();
            }
            if (snapshot.email() == null || snapshot.email().isBlank()) {
                if (afterSend) throw new ReauthenticationEmailRequestConflictException();
                throw new CurrentEmailNotBoundException();
            }
            String email = snapshot.email().trim().toLowerCase(Locale.ROOT);
            try {
                if (email.indexOf('\r') >= 0 || email.indexOf('\n') >= 0) throw new AddressException();
                new InternetAddress(email, true).validate();
            } catch (AddressException failure) {
                throw new ReauthenticationEmailRequestUnavailableException(failure);
            }
            return new ReauthenticationEmailBinding(actor.userId(), email, snapshot.tokenVersion());
        } catch (DataAccessException | TransactionException failure) {
            throw new ReauthenticationEmailRequestUnavailableException(failure);
        }
    }

    private static String digest(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required digest algorithm unavailable");
        }
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
