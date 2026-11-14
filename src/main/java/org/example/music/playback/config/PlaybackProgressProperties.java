package org.example.music.playback.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.playback-progress")
public record PlaybackProgressProperties(@DefaultValue("120") @Min(1) int rateLimitPerMinute) {
}
