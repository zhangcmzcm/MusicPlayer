package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.example.music.verification.service.EmailVerificationCodeService;
import org.example.music.verification.service.VerifiedEmailCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterCommitEmailVerificationCodeConsumerTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void should_consume_code_only_after_transaction_commit() {
        EmailVerificationCodeService codeService = mock(EmailVerificationCodeService.class);
        AfterCommitEmailVerificationCodeConsumer consumer = new AfterCommitEmailVerificationCodeConsumer(codeService);
        VerifiedEmailCode verified = new VerifiedEmailCode("alice@example.com", "code-version");
        TransactionSynchronizationManager.initSynchronization();

        consumer.registerAfterCommit(verified);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(synchronization -> { synchronization.beforeCommit(false); });
        verify(codeService, org.mockito.Mockito.never()).consumeAfterCommit(verified);
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(codeService).consumeAfterCommit(verified);
    }

    @Test
    void should_reject_registration_without_active_transaction() {
        EmailVerificationCodeService codeService = mock(EmailVerificationCodeService.class);
        AfterCommitEmailVerificationCodeConsumer consumer = new AfterCommitEmailVerificationCodeConsumer(codeService);

        assertThrows(
                IllegalStateException.class,
                () -> consumer.registerAfterCommit(
                        new VerifiedEmailCode("alice@example.com", "code-version")
                )
        );
    }
}