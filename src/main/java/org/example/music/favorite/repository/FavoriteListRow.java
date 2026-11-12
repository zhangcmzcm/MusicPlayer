package org.example.music.favorite.repository;

import java.time.Instant;
import org.example.music.track.domain.TrackStatus;

public record FavoriteListRow(Long id, String title, String artistName, String albumName,
                              Long durationMs, TrackStatus status, Long coverAssetId, Instant favoritedAt) {
}
