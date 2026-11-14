package org.example.music.playback.repository;

import java.time.Instant;

public record PlaybackProgressRow(
        String deviceId, Long trackId, long positionSeconds, long sequence, Instant updatedAt
) {
}
