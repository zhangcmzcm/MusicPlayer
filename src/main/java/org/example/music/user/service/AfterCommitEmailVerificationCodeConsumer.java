package org.example.music.user.service;

import org.example.music.verification.service.EmailVerificationCodeService;
import org.example.music.verification.service.VerifiedEmailCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AfterCommitEmailVerificationCodeConsumer {

    private final EmailVerificationCodeService emailVerificationCodeService;

    public AfterCommitEmailVerificationCodeConsumer(EmailVerificationCodeService emailVerificationCodeService) {
        this.emailVerificationCodeService = emailVerificationCodeService;
    }

    public void registerAfterCommit(VerifiedEmailCode verifiedEmailCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Registration must run inside an active transaction");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailVerificationCodeService.consumeAfterCommit(verifiedEmailCode);
            }
        });
    }
}