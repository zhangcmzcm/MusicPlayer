package org.example.music.playback.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlaybackProgressProperties.class)
public class PlaybackProgressConfig {
}
