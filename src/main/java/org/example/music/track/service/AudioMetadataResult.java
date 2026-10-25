package org.example.music.track.service;

import java.util.Optional;

public record AudioMetadataResult(
        AudioMetadata metadata,
        Optional<ExtractedArtwork> artwork,
        boolean artworkTooLarge
) {
}
