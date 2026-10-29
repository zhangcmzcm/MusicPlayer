package org.example.music.track.dto;

import java.time.ZoneOffset;
import org.example.music.track.domain.TrackEntity;

public final class TrackArchiveResponseMapper {

    private TrackArchiveResponseMapper() {
    }

    public static TrackArchiveResponse from(TrackEntity track) {
        return new TrackArchiveResponse(
                track.getId(),
                track.getStatus().name(),
                track.getVersion(),
                track.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}