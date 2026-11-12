package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class UnfavoriteTrackServiceTest {
    private final UnfavoriteTrackTransactionService transaction = mock(UnfavoriteTrackTransactionService.class);
    private final UnfavoriteTrackService service = new UnfavoriteTrackService(transaction);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);

    static Stream<RuntimeException> failures() {
        return Stream.of(new DataAccessResourceFailureException("private query"),
                new DataAccessResourceFailureException("private delete"),
                new DataIntegrityViolationException("private constraint"),
                new CannotAcquireLockException("private lock"),
                new CannotCreateTransactionException("private begin"),
                new TransactionSystemException("private commit"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void should_translate_storage_and_transaction_failures(RuntimeException failure) {
        doThrow(failure).when(transaction).unfavorite(42L, actor);
        var error = assertThrows(FavoriteStoreUnavailableException.class, () -> service.unfavorite(42L, actor));
        assertSame(failure, error.getCause());
    }

    @Test
    void should_wait_for_transaction_before_returning_success() {
        service.unfavorite(42L, actor);
        verify(transaction).unfavorite(42L, actor);
    }
}
