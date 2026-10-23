package org.example.music.common.util;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SecureVerificationCodeGenerator {

    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        return String.format("%06d", secureRandom.nextInt(CODE_BOUND));
    }
}