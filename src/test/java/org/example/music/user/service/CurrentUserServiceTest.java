package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserProfileSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.UserProfileStoreUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class CurrentUserServiceTest {
    private final UserRepository repository = mock(UserRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(42, UserRole.USER);
    private CurrentUserService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            assertTrue(((TransactionDefinition) invocation.getArgument(0)).isReadOnly());
            return status;
        });
        service = new CurrentUserService(repository, transactions);
    }

    @Test
    void should_query_only_actor_and_keep_roles_distinct() {
        when(repository.findProfileSnapshot(42)).thenReturn(Optional.of(new UserProfileSnapshot(
                42, "alice", "alice@example.com", UserRole.ADMIN, "2026-10-21 12:34:56")));
        var result = service.getCurrentUser(actor);
        assertEquals(42, result.id());
        assertEquals("alice", result.username());
        assertEquals("alice@example.com", result.email());
        assertEquals(UserRole.ADMIN, result.userRole());
        assertEquals(UserRole.USER, result.loginRole());
        assertEquals(Instant.parse("2026-10-21T12:34:56Z"), result.createdAt());
        verify(repository).findProfileSnapshot(42);
        verifyNoMoreInteractions(repository);
        verify(transactions).commit(status);
    }

    @Test
    void should_preserve_nulls_and_read_repeatedly_without_writes() {
        when(repository.findProfileSnapshot(42)).thenReturn(Optional.of(
                new UserProfileSnapshot(42, "alice", null, UserRole.USER, null)));
        assertNull(service.getCurrentUser(actor).email());
        assertNull(service.getCurrentUser(actor).createdAt());
        verify(repository, times(2)).findProfileSnapshot(42);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void should_revoke_when_account_disappears() {
        when(repository.findProfileSnapshot(42)).thenReturn(Optional.empty());
        assertThrows(CurrentUserRevokedException.class, () -> service.getCurrentUser(actor));
    }

    @Test
    void should_translate_query_failure() {
        when(repository.findProfileSnapshot(42)).thenThrow(new DataAccessResourceFailureException("database"));
        assertThrows(UserProfileStoreUnavailableException.class, () -> service.getCurrentUser(actor));
    }

    @Test
    void should_translate_transaction_start_failure() {
        doThrow(new CannotCreateTransactionException("start")).when(transactions).getTransaction(any());
        assertThrows(UserProfileStoreUnavailableException.class, () -> service.getCurrentUser(actor));
        verifyNoInteractions(repository);
    }

    @Test
    void should_translate_transaction_commit_failure() {
        when(repository.findProfileSnapshot(42)).thenReturn(Optional.of(
                new UserProfileSnapshot(42, "alice", null, UserRole.USER, null)));
        doThrow(new TransactionSystemException("commit")).when(transactions).commit(status);
        assertThrows(UserProfileStoreUnavailableException.class, () -> service.getCurrentUser(actor));
    }
}
