package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserProfileSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UpdateCurrentUserRequest;
import org.example.music.user.exception.CurrentUserRevokedException;
import org.example.music.user.exception.UserAlreadyExistsException;
import org.example.music.user.exception.UserProfileUpdateUnavailableException;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;

class CurrentUserUpdateServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(42, UserRole.USER);
    private final UpdateCurrentUserRequest request = new UpdateCurrentUserRequest("Alice_new");
    private CurrentUserUpdateService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            assertFalse(((TransactionDefinition) invocation.getArgument(0)).isReadOnly());
            return status;
        });
        service = new CurrentUserUpdateService(users, transactions);
    }

    @ParameterizedTest
    @CsvSource({"0", "1"})
    void should_update_only_actor_then_read_profile_and_commit(int changed) {
        when(users.updateUsernameIfChanged(42, "Alice_new")).thenReturn(changed);
        when(users.findProfileSnapshot(42)).thenReturn(Optional.of(new UserProfileSnapshot(
                42, "Alice_new", "alice@example.com", UserRole.ADMIN, "2026-10-21 12:34:56.123456")));
        var result = service.updateCurrentUser(actor, request);
        assertEquals(42, result.id());
        assertEquals("Alice_new", result.username());
        assertEquals("alice@example.com", result.email());
        assertEquals(UserRole.ADMIN, result.userRole());
        assertEquals(UserRole.USER, result.loginRole());
        assertEquals(Instant.parse("2026-10-21T12:34:56.123456Z"), result.createdAt());
        var order = inOrder(users, transactions);
        order.verify(transactions).getTransaction(any());
        order.verify(users).updateUsernameIfChanged(42, "Alice_new");
        order.verify(users).findProfileSnapshot(42);
        order.verify(transactions).commit(status);
        verifyNoMoreInteractions(users);
    }

    @Test
    void should_preserve_null_fields() {
        when(users.findProfileSnapshot(42)).thenReturn(Optional.of(
                new UserProfileSnapshot(42, "Alice_new", null, UserRole.USER, null)));
        var result = service.updateCurrentUser(actor, request);
        assertNull(result.email());
        assertNull(result.createdAt());
    }

    @Test
    void should_revoke_and_roll_back_when_profile_disappears() {
        when(users.findProfileSnapshot(42)).thenReturn(Optional.empty());
        assertThrows(CurrentUserRevokedException.class, () -> service.updateCurrentUser(actor, request));
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    @Test
    void should_translate_only_mysql_duplicate_key_from_username_update() {
        when(users.updateUsernameIfChanged(42, "Alice_new")).thenThrow(new DataIntegrityViolationException(
                "private index details", new SQLException("private occupant", "23000", 1062)));
        var error = assertThrows(UserAlreadyExistsException.class, () -> service.updateCurrentUser(actor, request));
        assertEquals("USERNAME_ALREADY_EXISTS", error.getErrorCode());
        assertEquals("用户名已占用", error.getMessage());
        verify(users, never()).findProfileSnapshot(any());
        verify(transactions).rollback(status);
    }

    @Test
    void should_find_duplicate_key_in_next_exception_chain() {
        SQLException wrapper = new SQLException("wrapper");
        wrapper.setNextException(new SQLException("duplicate", "23000", 1062));
        when(users.updateUsernameIfChanged(42, "Alice_new")).thenThrow(
                new DataIntegrityViolationException("wrapper", wrapper));
        assertThrows(UserAlreadyExistsException.class, () -> service.updateCurrentUser(actor, request));
    }

    @ParameterizedTest
    @CsvSource({"23000,1048", "42000,1062", "40001,1213", "HY000,1205"})
    void should_not_report_other_database_failures_as_username_conflict(String state, int code) {
        when(users.updateUsernameIfChanged(42, "Alice_new")).thenThrow(
                new DataIntegrityViolationException("database", new SQLException("failure", state, code)));
        assertThrows(UserProfileUpdateUnavailableException.class, () -> service.updateCurrentUser(actor, request));
    }

    @Test
    void should_not_report_unknown_integrity_failure_as_conflict() {
        when(users.updateUsernameIfChanged(42, "Alice_new")).thenThrow(new DataIntegrityViolationException("unknown"));
        assertThrows(UserProfileUpdateUnavailableException.class, () -> service.updateCurrentUser(actor, request));
    }

    @Test
    void should_not_classify_projection_failure_as_username_conflict() {
        when(users.findProfileSnapshot(42)).thenThrow(new DataIntegrityViolationException("projection",
                new SQLException("not from update", "23000", 1062)));
        assertThrows(UserProfileUpdateUnavailableException.class, () -> service.updateCurrentUser(actor, request));
        verify(transactions).rollback(status);
    }

    @Test
    void should_roll_back_update_when_projection_fails() {
        when(users.findProfileSnapshot(42)).thenThrow(new DataAccessResourceFailureException("database"));
        assertThrows(UserProfileUpdateUnavailableException.class, () -> service.updateCurrentUser(actor, request));
        verify(users).updateUsernameIfChanged(42, "Alice_new");
        verify(transactions).rollback(status);
    }

    @Test
    void should_roll_back_when_response_cannot_be_assembled() {
        when(users.findProfileSnapshot(42)).thenReturn(Optional.of(
                new UserProfileSnapshot(42, "Alice_new", null, UserRole.USER, "invalid-date")));
        assertThrows(DateTimeParseException.class, () -> service.updateCurrentUser(actor, request));
        verify(transactions).rollback(status);
    }

    @Test
    void should_translate_transaction_start_failure() {
        doThrow(new CannotCreateTransactionException("start")).when(transactions).getTransaction(any());
        assertThrows(UserProfileUpdateUnavailableException.class, () -> service.updateCurrentUser(actor, request));
        verifyNoInteractions(users);
    }

    @Test
    void should_not_return_success_or_conflict_when_commit_confirmation_fails() {
        when(users.findProfileSnapshot(42)).thenReturn(Optional.of(
                new UserProfileSnapshot(42, "Alice_new", null, UserRole.USER, null)));
        doThrow(new TransactionSystemException("commit", new SQLException("commit", "23000", 1062)))
                .when(transactions).commit(status);
        assertThrows(UserProfileUpdateUnavailableException.class, () -> service.updateCurrentUser(actor, request));
    }
}
