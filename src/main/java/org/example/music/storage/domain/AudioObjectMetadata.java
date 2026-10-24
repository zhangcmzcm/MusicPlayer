package org.example.music.storage.domain;

public record AudioObjectMetadata(
        String bucket,
        String objectKey,
        String contentType,
        long sizeBytes,
        String sha256
) {
}
