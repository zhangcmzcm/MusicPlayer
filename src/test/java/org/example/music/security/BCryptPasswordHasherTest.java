package org.example.music.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BCryptPasswordHasherTest {

    private final PasswordHasher passwordHasher = new BCryptPasswordHasher();

    @Test
    void should_hash_password_without_storing_raw_password() {
        String rawPassword = "correct-password";

        String encodedPassword = passwordHasher.hash(rawPassword);

        assertNotEquals(rawPassword, encodedPassword);
        assertTrue(passwordHasher.matches(rawPassword, encodedPassword));
    }

    @Test
    void should_not_match_wrong_password() {
        String encodedPassword = passwordHasher.hash("correct-password");

        assertFalse(passwordHasher.matches("wrong-password", encodedPassword));
    }

}