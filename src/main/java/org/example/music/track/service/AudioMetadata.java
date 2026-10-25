package org.example.music.track.service;

import java.nio.file.Path;
import java.util.Map;
import org.example.music.track.domain.AudioFileFormat;

public record AudioMetadata(
        AudioFileFormat format,
        String rawTitle,
        String rawArtistName,
        String rawAlbumName,
        Long durationMs,
        Integer rawTrackNumber,
        Integer rawDiscNumber,
        Map<String, Object> rawMetadata
) {
}
