package org.example.music.track.service;

import java.util.Locale;
import java.util.Set;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackCoverNotFoundException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackCoverQueryService {
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private final TrackRepository tracks;
    private final MediaAssetRepository assets;

    public TrackCoverQueryService(TrackRepository tracks, MediaAssetRepository assets) {
        this.tracks = tracks;
        this.assets = assets;
    }

    @Transactional(readOnly = true)
    public TrackCoverSnapshot load(Long trackId, AuthenticatedUserPrincipal actor) {
        var track = tracks.findById(trackId).orElseThrow(() -> new TrackNotFoundException(trackId));
        if (actor.loginRole() != UserRole.ADMIN && track.getStatus() != TrackStatus.PUBLISHED) {
            throw new TrackNotFoundException(trackId);
        }
        if (track.getCoverAssetId() == null) {
            throw new TrackCoverNotFoundException();
        }
        var asset = assets.findById(track.getCoverAssetId())
                .orElseThrow(() -> new IllegalStateException("Cover asset record is missing"));
        if (asset.getKind() != MediaAssetKind.COVER || asset.getContentType() == null
                || !TYPES.contains(asset.getContentType()) || asset.getSizeBytes() == null
                || asset.getSizeBytes() <= 0 || asset.getSha256() == null
                || !asset.getSha256().matches("[0-9a-fA-F]{64}")
                || asset.getBucket() == null || asset.getBucket().isBlank()
                || asset.getObjectKey() == null || asset.getObjectKey().isBlank()) {
            throw new IllegalStateException("Cover asset metadata is invalid");
        }
        return new TrackCoverSnapshot(trackId, asset.getBucket(), asset.getObjectKey(),
                asset.getContentType(), asset.getSizeBytes(), asset.getSha256().toLowerCase(Locale.ROOT));
    }
}
