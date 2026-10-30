package org.example.music.track.repository;

import org.example.music.track.domain.TrackStatus;

public record TrackListRow(Long id, String title, String artistName, String albumName,
                           Long durationMs, TrackStatus status, Long coverAssetId) {
}
