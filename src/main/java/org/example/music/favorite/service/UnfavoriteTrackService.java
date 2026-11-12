package org.example.music.favorite.service;

import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class UnfavoriteTrackService {
    private final UnfavoriteTrackTransactionService transaction;

    public UnfavoriteTrackService(UnfavoriteTrackTransactionService transaction) {
        this.transaction = transaction;
    }

    @PreAuthorize("isAuthenticated()")
    public void unfavorite(Long trackId, AuthenticatedUserPrincipal actor) {
        try {
            transaction.unfavorite(trackId, actor);
        } catch (DataAccessException | TransactionException exception) {
            throw new FavoriteStoreUnavailableException(exception);
        }
    }
}
