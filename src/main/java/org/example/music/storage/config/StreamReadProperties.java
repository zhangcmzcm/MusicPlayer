package org.example.music.storage.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="app.storage.stream")
public record StreamReadProperties(@DefaultValue("50") @Min(1) int maxConnections) {
}
