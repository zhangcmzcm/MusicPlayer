package org.example.music.track.dto;

import java.time.ZoneOffset;
import org.example.music.track.domain.TrackEntity;

public final class TrackPublishResponseMapper {

    private TrackPublishResponseMapper() {
    }

    public static TrackPublishResponse from(TrackEntity track) {
        return new TrackPublishResponse(
                track.getId(),
                track.getStatus().name(),
                track.getVersion(),
                track.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
