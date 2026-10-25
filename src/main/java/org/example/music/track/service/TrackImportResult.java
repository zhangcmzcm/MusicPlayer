package org.example.music.track.service;

import java.util.Set;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaImportEntity;
import org.example.music.track.domain.MetadataWarning;
import org.example.music.track.domain.TrackEntity;

public record TrackImportResult(
        MediaImportEntity importEntity,
        TrackEntity track,
        MediaAssetEntity audioAsset,
        MediaAssetEntity coverAsset,
        Set<MetadataWarning> warnings,
        boolean created
) {
}
