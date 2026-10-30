package org.example.music.track.service;

import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackDetailResponse;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserRole;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackDetailService {
    private final TrackRepository trackRepository;
    private final MediaAssetRepository mediaAssetRepository;

    public TrackDetailService(TrackRepository trackRepository, MediaAssetRepository mediaAssetRepository) {
        this.trackRepository = trackRepository;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public TrackDetailResponse getDetail(Long trackId, AuthenticatedUserPrincipal actor) {
        try {
            TrackEntity track = trackRepository.findById(trackId)
                    .orElseThrow(() -> new TrackNotFoundException(trackId));
            if (actor.loginRole() != UserRole.ADMIN && track.getStatus() != TrackStatus.PUBLISHED) {
                throw new TrackNotFoundException(trackId);
            }
            TrackDetailResponse.Cover cover = null;
            if (track.getCoverAssetId() != null) {
                MediaAssetEntity asset = mediaAssetRepository.findById(track.getCoverAssetId())
                        .orElseThrow(() -> new IllegalStateException("Track cover asset is missing"));
                if (asset.getKind() != MediaAssetKind.COVER) {
                    throw new IllegalStateException("Track cover asset has an invalid kind");
                }
                cover = new TrackDetailResponse.Cover("/api/v1/tracks/" + track.getId() + "/cover",
                        asset.getContentType(), asset.getSizeBytes());
            }
            return new TrackDetailResponse(track.getId(), track.getTitle(), track.getArtistName(),
                    track.getAlbumName(), track.getDurationMs(), track.getTrackNumber(), track.getDiscNumber(),
                    track.getStatus().name(), cover);
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track could not be read", exception);
        }
    }
}
