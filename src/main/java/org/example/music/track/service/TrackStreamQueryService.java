package org.example.music.track.service;

import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackStreamQueryService {
    private static final Set<String> TYPES=Set.of("audio/mpeg","audio/flac");
    private final TrackRepository tracks;
    private final MediaAssetRepository assets;
    public TrackStreamQueryService(TrackRepository tracks,MediaAssetRepository assets) { this.tracks=tracks;this.assets=assets; }

    @Transactional(readOnly=true)
    public TrackStreamSnapshot load(Long trackId,AuthenticatedUserPrincipal actor) {
        var track=tracks.findById(trackId).orElseThrow(()->new TrackNotFoundException(trackId));
        if (actor.loginRole()!=UserRole.ADMIN && track.getStatus()!=TrackStatus.PUBLISHED) { throw new TrackNotFoundException(trackId); }
        var asset=assets.findById(track.getAudioAssetId()).orElseThrow(()->new IllegalStateException("Audio asset record missing"));
        if (asset.getKind()!=MediaAssetKind.AUDIO || asset.getContentType()==null || !TYPES.contains(asset.getContentType())
                || asset.getSizeBytes()==null || asset.getSizeBytes()<=0 || asset.getSha256()==null
                || !asset.getSha256().matches("[0-9a-fA-F]{64}") || asset.getBucket()==null || asset.getBucket().isBlank()
                || asset.getObjectKey()==null || asset.getObjectKey().isBlank() || asset.getCreatedAt()==null) {
            throw new IllegalStateException("Audio asset metadata invalid");
        }
        return new TrackStreamSnapshot(trackId,new AudioObjectMetadata(asset.getBucket(),asset.getObjectKey(),asset.getContentType(),
                asset.getSizeBytes(),asset.getSha256().toLowerCase(Locale.ROOT)),asset.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
