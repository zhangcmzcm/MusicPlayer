package org.example.music.track.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.track-search")
public record TrackSearchProperties(
        @DefaultValue("60") @Min(1) int rateLimitPerMinute,
        @DefaultValue("2000") @Min(1) int queryTimeoutMs
) {
}
