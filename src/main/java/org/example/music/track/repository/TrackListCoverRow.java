package org.example.music.track.repository;

import org.example.music.track.domain.MediaAssetKind;

public record TrackListCoverRow(Long id, MediaAssetKind kind, String contentType, Long sizeBytes,
                                String sha256, String bucket, String objectKey) {
}
