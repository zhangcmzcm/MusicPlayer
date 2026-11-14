package org.example.music.playback.service;

import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.dto.SavePlaybackProgressResponse;
import org.example.music.playback.exception.PlaybackProgressStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class PlaybackProgressService {
    private final PlaybackProgressRateLimiter limiter;
    private final PlaybackProgressTransactionService transaction;

    public PlaybackProgressService(PlaybackProgressRateLimiter limiter, PlaybackProgressTransactionService transaction) {
        this.limiter = limiter;
        this.transaction = transaction;
    }

    @PreAuthorize("isAuthenticated()")
    public SavePlaybackProgressResponse save(String deviceId, SavePlaybackProgressRequest input,
            AuthenticatedUserPrincipal actor) {
        limiter.consumeOrThrow(actor.userId());
        try {
            return transaction.save(deviceId, input, actor);
        } catch (DataAccessException | TransactionException failure) {
            throw new PlaybackProgressStoreUnavailableException(failure);
        }
    }
}
