package org.example.music.track.service;

import java.time.Instant;
import org.example.music.storage.domain.AudioObjectMetadata;

public record TrackStreamSnapshot(Long trackId, AudioObjectMetadata metadata, Instant createdAt) { }
