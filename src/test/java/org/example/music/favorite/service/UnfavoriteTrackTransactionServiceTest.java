package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UnfavoriteTrackTransactionServiceTest {
    private final FavoriteRepository favorites = mock(FavoriteRepository.class);
    private final UnfavoriteTrackTransactionService service = new UnfavoriteTrackTransactionService(favorites);

    @ParameterizedTest
    @CsvSource({"USER,DRAFT", "USER,PUBLISHED", "USER,ARCHIVED", "ADMIN,DRAFT", "ADMIN,PUBLISHED", "ADMIN,ARCHIVED"})
    void should_delete_own_favorite_for_every_status_and_login_role(UserRole role, TrackStatus status) {
        when(favorites.lockTrackStatus(42L)).thenReturn(Optional.of(status));
        service.unfavorite(42L, new AuthenticatedUserPrincipal(7, role));
        var order = inOrder(favorites);
        order.verify(favorites).lockTrackStatus(42L);
        order.verify(favorites).deleteByUserAndTrack(7, 42L);
        verifyNoMoreInteractions(favorites);
    }

    @Test
    void should_succeed_when_track_is_missing() {
        when(favorites.lockTrackStatus(42L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.unfavorite(42L, new AuthenticatedUserPrincipal(7, UserRole.USER)));
        verify(favorites).lockTrackStatus(42L);
        verifyNoMoreInteractions(favorites);
    }

    @ParameterizedTest
    @CsvSource({"0", "1"})
    void should_succeed_for_both_delete_counts(int changed) {
        when(favorites.lockTrackStatus(42L)).thenReturn(Optional.of(TrackStatus.PUBLISHED));
        when(favorites.deleteByUserAndTrack(7, 42L)).thenReturn(changed);
        assertDoesNotThrow(() -> service.unfavorite(42L, new AuthenticatedUserPrincipal(7, UserRole.USER)));
        verify(favorites).deleteByUserAndTrack(7, 42L);
    }
}
