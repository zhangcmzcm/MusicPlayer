package org.example.music.storage.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String audioBucket,
        String coversBucket,
        Duration connectTimeout,
        Duration readTimeout
) {
}
