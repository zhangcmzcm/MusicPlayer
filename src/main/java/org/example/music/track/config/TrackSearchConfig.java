package org.example.music.track.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TrackSearchProperties.class)
public class TrackSearchConfig {
}
