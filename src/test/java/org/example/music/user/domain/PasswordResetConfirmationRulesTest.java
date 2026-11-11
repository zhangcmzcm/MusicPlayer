package org.example.music.user.domain;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.time.Instant;
import org.example.music.user.exception.PasswordResetConfirmationUnavailableException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;

class PasswordResetConfirmationRulesTest {
    static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    final PasswordResetConfirmationRules rules = new PasswordResetConfirmationRules(new PasswordResetEmailRules(FACTORY.getValidator()));
    final String hash = "$2a$10$" + "a".repeat(53);
    final PasswordResetAccountSnapshot account = new PasswordResetAccountSnapshot(1,"ALICE@example.com",UserRole.USER,Long.MAX_VALUE);
    PasswordResetChallengeSnapshot snapshot(String email, int attempts) {
        return new PasswordResetChallengeSnapshot("A".repeat(43), PasswordResetIdentifiers.digest(email),email,
                new PasswordResetAdmission(Instant.ofEpochMilli(1),Instant.ofEpochMilli(300001)), account, hash, attempts);
    }
    @AfterAll static void close() { FACTORY.close(); }
    @Test void should_preserve_long_max_version_and_raw_email_and_redact() {
        var value = snapshot("alice@example.com",4);
        assertDoesNotThrow(() -> rules.validateChallenge(value));
        assertTrue(rules.matches(value, account));
        assertFalse(rules.matches(value,new PasswordResetAccountSnapshot(1,account.email(),UserRole.USER,0)));
        assertFalse(value.toString().contains(hash));
    }
    @Test void should_reject_invalid_binding_attempts_and_hash() {
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.validateChallenge(snapshot("other@example.com",0)));
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.validateChallenge(snapshot("alice@example.com",5)));
        for (String invalid : new String[]{"", "$2a$03$"+"a".repeat(53), "$2a$32$"+"a".repeat(53)}) {
            assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.requirePasswordHash(invalid));
        }
    }
    static Stream<String> supportedHashes() {
        return Stream.of("2a", "2b", "2y").flatMap(prefix -> Stream.of("04", "31")
                .map(cost -> "$" + prefix + "$" + cost + "$" + "a".repeat(53)));
    }

    static Stream<String> unsupportedHashes() {
        return Stream.of(null, "", " ", "Legacy-V1", "$2a$03$" + "a".repeat(53),
                "$2a$32$" + "a".repeat(53), "$2a$10$" + "a".repeat(52),
                "$2a$10$" + "a".repeat(54), "$2a$10$" + "a".repeat(52) + "!",
                " $2a$10$" + "a".repeat(53), "$2a$10$" + "a".repeat(53) + " ",
                "$2x$10$" + "a".repeat(53));
    }

    @ParameterizedTest @MethodSource("supportedHashes")
    void should_recognize_supported_bcrypt_and_keep_strict_validation(String value) {
        assertTrue(rules.isSupportedPasswordHash(value));
        assertDoesNotThrow(() -> rules.requirePasswordHash(value));
        assertDoesNotThrow(() -> rules.requireStoredPasswordHash(value));
    }

    @ParameterizedTest @MethodSource("unsupportedHashes")
    void should_reject_unsupported_new_and_verification_hashes(String value) {
        assertFalse(rules.isSupportedPasswordHash(value));
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.requirePasswordHash(value));
        var original = snapshot("alice@example.com", 0);
        var invalid = new PasswordResetChallengeSnapshot(original.digest(), original.emailDigest(), original.email(),
                original.admission(), original.account(), value, original.attempts());
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.validateChallenge(invalid));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\r\n", "\u2003"})
    void should_reject_null_or_blank_stored_values(String value) {
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> rules.requireStoredPasswordHash(value));
    }

    @ParameterizedTest @ValueSource(strings = {"Legacy-V1", " Legacy-V1 ", "0123456789abcdef0123456789abcdef"})
    void should_accept_nonblank_historical_values(String value) {
        assertDoesNotThrow(() -> rules.requireStoredPasswordHash(value));
        assertFalse(rules.isSupportedPasswordHash(value));
    }

}
