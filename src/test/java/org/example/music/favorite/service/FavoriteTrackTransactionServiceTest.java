package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FavoriteTrackTransactionServiceTest {
    private final FavoriteRepository repository = mock(FavoriteRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00.123456789Z"), ZoneOffset.UTC);
    private final FavoriteTrackTransactionService service = new FavoriteTrackTransactionService(repository, clock);

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_allow_admin_to_favorite_each_existing_status(TrackStatus status) {
        when(repository.lockTrackStatus(42L)).thenReturn(Optional.of(status));
        service.favorite(42L, new AuthenticatedUserPrincipal(7, UserRole.ADMIN));
        var order = inOrder(repository);
        order.verify(repository).lockTrackStatus(42L);
        order.verify(repository).addIfAbsent(7, 42L, LocalDateTime.parse("2026-11-12T00:00:00.123456"));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void should_allow_user_to_favorite_published_track() {
        when(repository.lockTrackStatus(42L)).thenReturn(Optional.of(TrackStatus.PUBLISHED));
        service.favorite(42L, new AuthenticatedUserPrincipal(7, UserRole.USER));
        verify(repository).addIfAbsent(7, 42L, LocalDateTime.parse("2026-11-12T00:00:00.123456"));
    }

    @ParameterizedTest
    @EnumSource(value = TrackStatus.class, names = {"DRAFT", "ARCHIVED"})
    void should_recheck_visibility_before_duplicate_insert(TrackStatus status) {
        when(repository.lockTrackStatus(42L)).thenReturn(Optional.of(status));
        assertThrows(TrackNotFoundException.class,
                () -> service.favorite(42L, new AuthenticatedUserPrincipal(7, UserRole.USER)));
        verify(repository, never()).addIfAbsent(any(), any(), any());
    }

    @Test
    void should_return_not_found_when_track_is_missing() {
        when(repository.lockTrackStatus(42L)).thenReturn(Optional.empty());
        assertThrows(TrackNotFoundException.class,
                () -> service.favorite(42L, new AuthenticatedUserPrincipal(7, UserRole.ADMIN)));
        verify(repository, never()).addIfAbsent(any(), any(), any());
    }
}
