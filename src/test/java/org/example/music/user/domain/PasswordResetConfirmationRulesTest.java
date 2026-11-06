package org.example.music.user.domain;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.time.Instant;
import org.example.music.user.exception.PasswordResetConfirmationUnavailableException;
import org.junit.jupiter.api.*;

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
}
