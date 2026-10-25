package org.example.music.track.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.track-import")
public record TrackImportProperties(
        String tempDir,
        long audioMaxBytes,
        long coverMaxBytes,
        int rateLimitPerMinute
) {
}
