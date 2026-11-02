package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.UserPasswordSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.ChangePasswordRequest;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.*;

class CurrentUserPasswordServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final PasswordChangeRateLimiter limiter = mock(PasswordChangeRateLimiter.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(42, UserRole.USER);
    private final ChangePasswordRequest request = new ChangePasswordRequest("old-pass", "new-pass");
    private CurrentUserPasswordService service;

    @BeforeEach
    void setup() {
        service = new CurrentUserPasswordService(users, hasher, limiter, transactions);
        when(users.findPasswordSnapshot(42)).thenReturn(Optional.of(new UserPasswordSnapshot(42, "old-hash", 7)));
        when(hasher.matches("old-pass", "old-hash")).thenReturn(true);
        when(hasher.hash("new-pass")).thenReturn("new-hash");
        when(users.changePasswordIfSnapshotMatches(42, "old-hash", 7, "new-hash")).thenReturn(1);
        when(transactions.getTransaction(any())).thenReturn(status);
    }

    @Test
    void should_verify_and_hash_before_opening_write_transaction() {
        service.changePassword(actor, request);
        var order = inOrder(limiter, users, hasher, transactions);
        order.verify(limiter).consumeOrThrow(42);
        order.verify(users).findPasswordSnapshot(42);
        order.verify(hasher).matches("old-pass", "old-hash");
        order.verify(hasher).matches("new-pass", "old-hash");
        order.verify(hasher).hash("new-pass");
        order.verify(transactions).getTransaction(argThat(definition -> !definition.isReadOnly()));
        order.verify(users).changePasswordIfSnapshotMatches(42, "old-hash", 7, "new-hash");
        order.verify(transactions).commit(status);
        verifyNoMoreInteractions(limiter, users, hasher, transactions);
    }

    @Test
    void should_reject_old_password_before_equivalence_or_hashing() {
        when(hasher.matches("old-pass", "old-hash")).thenReturn(false);
        assertThrows(OldPasswordIncorrectException.class, () -> service.changePassword(actor, request));
        verify(hasher, never()).matches("new-pass", "old-hash");
        verify(hasher, never()).hash(any());
        verifyNoInteractions(transactions);
    }

    @Test
    void should_reject_equivalent_new_password_without_hashing() {
        when(hasher.matches("new-pass", "old-hash")).thenReturn(true);
        assertThrows(NewPasswordSameAsOldException.class, () -> service.changePassword(actor, request));
        verify(hasher, never()).hash(any());
        verifyNoInteractions(transactions);
    }

    @Test
    void should_stop_before_snapshot_when_limiter_fails() {
        doThrow(new PasswordChangeRateLimitUnavailableException(null)).when(limiter).consumeOrThrow(42);
        assertThrows(PasswordChangeRateLimitUnavailableException.class, () -> service.changePassword(actor, request));
        verifyNoInteractions(users, hasher, transactions);
    }

    @Test
    void should_revoke_missing_account_before_hashing() {
        when(users.findPasswordSnapshot(42)).thenReturn(Optional.empty());
        assertThrows(CurrentUserRevokedException.class, () -> service.changePassword(actor, request));
        verifyNoInteractions(hasher, transactions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void should_distinguish_missing_account_from_snapshot_conflict(boolean exists) {
        when(users.changePasswordIfSnapshotMatches(42, "old-hash", 7, "new-hash")).thenReturn(0);
        when(users.existsById(42)).thenReturn(exists);
        Class<? extends RuntimeException> expected = exists ? PasswordChangeConflictException.class : CurrentUserRevokedException.class;
        assertThrows(expected,
                () -> service.changePassword(actor, request));
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
        verify(users, times(1)).findPasswordSnapshot(42);
    }

    @Test
    void should_refuse_version_overflow() {
        when(users.findPasswordSnapshot(42)).thenReturn(Optional.of(
                new UserPasswordSnapshot(42, "old-hash", Long.MAX_VALUE)));
        assertThrows(PasswordChangeStoreUnavailableException.class, () -> service.changePassword(actor, request));
        verify(users, never()).changePasswordIfSnapshotMatches(any(), any(), anyLong(), any());
        verifyNoInteractions(transactions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "begin", "update", "exists", "commit"})
    void should_translate_storage_failures_including_transaction_boundaries(String stage) {
        var failure = new DataAccessResourceFailureException("private database diagnostic");
        switch (stage) {
            case "read" -> when(users.findPasswordSnapshot(42)).thenThrow(failure);
            case "begin" -> when(transactions.getTransaction(any())).thenThrow(new CannotCreateTransactionException("private"));
            case "update" -> when(users.changePasswordIfSnapshotMatches(42, "old-hash", 7, "new-hash")).thenThrow(failure);
            case "exists" -> {
                when(users.changePasswordIfSnapshotMatches(42, "old-hash", 7, "new-hash")).thenReturn(0);
                when(users.existsById(42)).thenThrow(failure);
            }
            case "commit" -> doThrow(new TransactionSystemException("private")).when(transactions).commit(status);
        }
        assertThrows(PasswordChangeStoreUnavailableException.class, () -> service.changePassword(actor, request));
        verify(limiter, times(1)).consumeOrThrow(42);
    }

    @Test
    void should_not_disguise_unexpected_hash_failure_as_store_failure() {
        when(hasher.hash("new-pass")).thenThrow(new IllegalArgumentException("unexpected hash failure"));
        assertThrows(IllegalArgumentException.class, () -> service.changePassword(actor, request));
        verifyNoInteractions(transactions);
    }

    @Test
    void should_not_accept_unexpected_update_count() {
        when(users.changePasswordIfSnapshotMatches(42, "old-hash", 7, "new-hash")).thenReturn(2);
        assertThrows(IllegalStateException.class, () -> service.changePassword(actor, request));
        verify(transactions).rollback(status);
    }
}
