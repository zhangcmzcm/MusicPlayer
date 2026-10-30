package org.example.music.track.service;

public record TrackCoverSnapshot(Long trackId, String bucket, String objectKey,
                                 String contentType, long sizeBytes, String sha256) {
}
