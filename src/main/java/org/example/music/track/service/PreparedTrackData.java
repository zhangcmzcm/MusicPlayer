package org.example.music.track.service;

import java.util.Set;
import org.example.music.track.domain.MetadataWarning;

public record PreparedTrackData(
        String title,
        String artistName,
        String albumName,
        long durationMs,
        Integer trackNumber,
        Integer discNumber,
        String id3MetadataJson,
        Set<MetadataWarning> warnings
) {
}
