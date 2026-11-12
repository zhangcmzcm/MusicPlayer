package org.example.music.favorite.service;

import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class FavoriteTrackService {
    private final FavoriteTrackTransactionService transaction;

    public FavoriteTrackService(FavoriteTrackTransactionService transaction) {
        this.transaction = transaction;
    }

    @PreAuthorize("isAuthenticated()")
    public void favorite(Long trackId, AuthenticatedUserPrincipal actor) {
        try {
            transaction.favorite(trackId, actor);
        } catch (DataAccessException | TransactionException exception) {
            throw new FavoriteStoreUnavailableException(exception);
        }
    }
}
