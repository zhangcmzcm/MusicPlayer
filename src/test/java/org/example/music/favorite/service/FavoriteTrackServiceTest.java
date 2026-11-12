package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class FavoriteTrackServiceTest {
    private final FavoriteTrackTransactionService transaction = mock(FavoriteTrackTransactionService.class);
    private final FavoriteTrackService service = new FavoriteTrackService(transaction);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);

    static Stream<RuntimeException> failures() {
        return Stream.of(new DataAccessResourceFailureException("private connection"),
                new DataIntegrityViolationException("private constraint"),
                new CannotAcquireLockException("private lock"),
                new CannotCreateTransactionException("private begin"),
                new TransactionSystemException("private commit"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void should_translate_storage_and_transaction_failures(RuntimeException failure) {
        doThrow(failure).when(transaction).favorite(42L, actor);
        var error = assertThrows(FavoriteStoreUnavailableException.class, () -> service.favorite(42L, actor));
        assertSame(failure, error.getCause());
    }

    @Test
    void should_wait_for_transaction_to_return_before_reporting_success() {
        service.favorite(42L, actor);
        verify(transaction).favorite(42L, actor);
    }

    @Test
    void should_preserve_not_found() {
        var failure = new TrackNotFoundException(42L);
        doThrow(failure).when(transaction).favorite(42L, actor);
        assertSame(failure, assertThrows(TrackNotFoundException.class, () -> service.favorite(42L, actor)));
    }
}
