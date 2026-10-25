package org.example.music.track.dto;

import java.util.List;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MetadataWarning;
import org.example.music.track.service.TrackImportResult;

public final class TrackImportResponseMapper {

    private TrackImportResponseMapper() {
    }

    public static TrackImportResponse from(TrackImportResult result) {
        MediaAssetEntity coverAsset = result.coverAsset();
        return new TrackImportResponse(
                result.importEntity().getId(),
                result.importEntity().getUploadId(),
                result.importEntity().getStatus().name(),
                new ImportedTrackResponse(
                        result.track().getId(),
                        result.track().getTitle(),
                        result.track().getArtistName(),
                        result.track().getAlbumName(),
                        result.track().getDurationMs(),
                        result.track().getTrackNumber(),
                        result.track().getDiscNumber(),
                        result.track().getStatus().name(),
                        asset(result.audioAsset()),
                        asset(coverAsset)
                ),
                result.warnings().stream().map(MetadataWarning::name).sorted().toList()
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
