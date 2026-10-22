package org.example.music.verification.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.verification.repository.VerificationCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationCodeFailureService {

    private final VerificationCodeRepository verificationCodeRepository;

    public EmailVerificationCodeFailureService(VerificationCodeRepository verificationCodeRepository) {
        this.verificationCodeRepository = verificationCodeRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long verificationCodeId) {
        verificationCodeRepository.incrementAttempts(
                verificationCodeId,
                LocalDateTime.now(ZoneOffset.UTC)
        );
        return currentAttempts(verificationCodeId);
    }

    private int currentAttempts(Long verificationCodeId) {
        return verificationCodeRepository.findById(verificationCodeId)
                .orElseThrow(() -> new IllegalStateException("Verification code disappeared during failure recording"))
                .getAttempts();
    }
}
