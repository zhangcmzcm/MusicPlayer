package org.example.music.track.service;

import java.nio.file.Path;

public record ExtractedArtwork(
        Path path,
        String contentType,
        long sizeBytes
) {
}
