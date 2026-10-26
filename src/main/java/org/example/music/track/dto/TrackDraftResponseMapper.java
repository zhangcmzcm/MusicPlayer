package org.example.music.track.dto;

import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.TrackEntity;

public final class TrackDraftResponseMapper {

    private TrackDraftResponseMapper() {
    }

    public static TrackDraftResponse from(
            TrackEntity track,
            MediaAssetEntity audioAsset,
            MediaAssetEntity coverAsset
    ) {
        return new TrackDraftResponse(
                track.getId(),
                track.getTitle(),
                track.getArtistName(),
                track.getAlbumName(),
                track.getDurationMs(),
                track.getTrackNumber(),
                track.getDiscNumber(),
                track.getVersion(),
                track.getStatus().name(),
                asset(audioAsset),
                asset(coverAsset)
        );
    }

    private static MediaAssetResponse asset(MediaAssetEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MediaAssetResponse(
                entity.getId(),
                entity.getContentType(),
                entity.getSizeBytes(),
                entity.getSha256()
        );
    }
}
