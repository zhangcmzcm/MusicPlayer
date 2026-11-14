package org.example.music.playback.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.exception.PlaybackProgressRateLimitUnavailableException;
import org.example.music.playback.exception.PlaybackProgressStaleSequenceException;
import org.example.music.playback.exception.PlaybackProgressStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class PlaybackProgressServiceTest {
    private final PlaybackProgressRateLimiter limiter = mock(PlaybackProgressRateLimiter.class);
    private final PlaybackProgressTransactionService transaction = mock(PlaybackProgressTransactionService.class);
    private final PlaybackProgressService service = new PlaybackProgressService(limiter, transaction);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);
    private final SavePlaybackProgressRequest input = new SavePlaybackProgressRequest(123L, 83L, 42L);

    @Test
    void should_consume_account_quota_before_transaction() {
        service.save("device", input, actor);
        var order = inOrder(limiter, transaction);
        order.verify(limiter).consumeOrThrow(7);
        order.verify(transaction).save("device", input, actor);
    }

    @Test
    void should_stop_before_transaction_when_quota_is_unavailable() {
        var failure = new PlaybackProgressRateLimitUnavailableException(null);
        doThrow(failure).when(limiter).consumeOrThrow(7);
        assertSame(failure, assertThrows(PlaybackProgressRateLimitUnavailableException.class,
                () -> service.save("device", input, actor)));
        verifyNoInteractions(transaction);
    }

    @Test
    void should_translate_data_access_and_commit_failures() {
        for (var failure : new RuntimeException[] {new DataAccessResourceFailureException("database private"),
                new TransactionSystemException("commit private")}) {
            doThrow(failure).when(transaction).save("device", input, actor);
            assertSame(failure, assertThrows(PlaybackProgressStoreUnavailableException.class,
                    () -> service.save("device", input, actor)).getCause());
        }
    }

    @Test
    void should_preserve_business_conflicts_and_consumed_quota() {
        var conflict = new PlaybackProgressStaleSequenceException(43);
        when(transaction.save("device", input, actor)).thenThrow(conflict);
        assertSame(conflict, assertThrows(PlaybackProgressStaleSequenceException.class,
                () -> service.save("device", input, actor)));
        verify(limiter).consumeOrThrow(7);
    }
}
