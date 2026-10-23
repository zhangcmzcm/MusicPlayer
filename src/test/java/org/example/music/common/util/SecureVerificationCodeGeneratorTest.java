package org.example.music.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecureVerificationCodeGeneratorTest {

    private final SecureVerificationCodeGenerator generator = new SecureVerificationCodeGenerator();

    @Test
    void should_generate_six_digit_code() {
        for (int index = 0; index < 1000; index++) {
            String code = generator.generate();

            assertEquals(6, code.length());
            assertTrue(code.chars().allMatch(Character::isDigit));
        }
    }
}