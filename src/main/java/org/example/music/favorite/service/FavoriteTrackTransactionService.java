package org.example.music.favorite.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteTrackTransactionService {
    private final FavoriteRepository favorites;
    private final Clock clock;

    public FavoriteTrackTransactionService(FavoriteRepository favorites, @Qualifier("authenticationClock") Clock clock) {
        this.favorites = favorites;
        this.clock = clock;
    }

    @Transactional
    public void favorite(Long trackId, AuthenticatedUserPrincipal actor) {
        // The locking read serializes visibility checks with archive and delete writes.
        TrackStatus status = favorites.lockTrackStatus(trackId).orElseThrow(() -> new TrackNotFoundException(trackId));
        if (actor.loginRole() != UserRole.ADMIN && status != TrackStatus.PUBLISHED) {
            throw new TrackNotFoundException(trackId);
        }
        LocalDateTime createdAt = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        favorites.addIfAbsent(actor.userId(), trackId, createdAt);
    }
}
