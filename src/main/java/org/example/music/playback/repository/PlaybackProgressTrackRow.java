package org.example.music.playback.repository;

import org.example.music.track.domain.TrackStatus;

public record PlaybackProgressTrackRow(TrackStatus status, Long durationMs) {
}
