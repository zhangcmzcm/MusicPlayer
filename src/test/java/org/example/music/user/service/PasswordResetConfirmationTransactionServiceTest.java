package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;

class PasswordResetConfirmationTransactionServiceTest {
    final UserRepository users=mock(UserRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus tx=mock(TransactionStatus.class);
    final PasswordResetConfirmationTransactionService updater=new PasswordResetConfirmationTransactionService(users,manager);
    final PasswordResetPreparedSnapshot expected=new PasswordResetPreparedSnapshot(42,"Old@Example.com",UserRole.USER,7,"oldHash");
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(tx);
        when(users.resetPasswordIfSnapshotMatches(42,expected.rawEmail(),"USER","oldHash",7,"newHash")).thenReturn(1);
    }
    void update() { updater.update(expected,"newHash"); }
    @Test void should_use_single_prepared_snapshot_and_commit() {
        update(); verify(manager).commit(tx);
        verify(users).resetPasswordIfSnapshotMatches(42,expected.rawEmail(),"USER","oldHash",7,"newHash"); verifyNoMoreInteractions(users);
    }
    @Test void should_rollback_conflict_without_rereading_or_retry() {
        when(users.resetPasswordIfSnapshotMatches(anyInt(),any(),any(),any(),anyLong(),any())).thenReturn(0);
        assertThrows(PasswordResetVerificationFailedException.class,this::update); verify(manager).rollback(tx);
        verify(users,times(1)).resetPasswordIfSnapshotMatches(anyInt(),any(),any(),any(),anyLong(),any()); verifyNoMoreInteractions(users);
    }
    @Test void should_fail_on_begin_commit_and_invalid_count() {
        doThrow(new CannotCreateTransactionException("secret")).when(manager).getTransaction(any());
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::update);
        doReturn(tx).when(manager).getTransaction(any()); doThrow(new TransactionSystemException("secret")).when(manager).commit(tx);
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::update);
        doNothing().when(manager).commit(tx);
        when(users.resetPasswordIfSnapshotMatches(anyInt(),any(),any(),any(),anyLong(),any())).thenReturn(2);
        assertThrows(IllegalStateException.class,this::update); verify(manager).rollback(tx);
    }
    @Test void should_reject_max_version_without_starting_transaction() {
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> updater.update(new PasswordResetPreparedSnapshot(42,expected.rawEmail(),UserRole.USER,Long.MAX_VALUE,"oldHash"),"newHash"));
        verifyNoInteractions(manager,users);
    }
}
