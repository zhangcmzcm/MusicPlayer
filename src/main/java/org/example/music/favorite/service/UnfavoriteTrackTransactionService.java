package org.example.music.favorite.service;

import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnfavoriteTrackTransactionService {
    private final FavoriteRepository favorites;

    public UnfavoriteTrackTransactionService(FavoriteRepository favorites) {
        this.favorites = favorites;
    }

    @Transactional
    public void unfavorite(Long trackId, AuthenticatedUserPrincipal actor) {
        if (favorites.lockTrackStatus(trackId).isEmpty()) {
            return;
        }
        favorites.deleteByUserAndTrack(actor.userId(), trackId);
    }
}
