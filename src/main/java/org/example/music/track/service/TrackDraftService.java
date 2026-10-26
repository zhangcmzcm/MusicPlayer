package org.example.music.track.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackDraftRequest;
import org.example.music.track.dto.TrackDraftResponse;
import org.example.music.track.dto.TrackDraftResponseMapper;
import org.example.music.track.exception.TrackDraftValidationException;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.exception.TrackVersionConflictException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class TrackDraftService {

    private static final int MAX_TEXT_LENGTH = 255;

    private final TrackRepository trackRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final TrackDraftTransactionService trackDraftTransactionService;

    public TrackDraftService(
            TrackRepository trackRepository,
            MediaAssetRepository mediaAssetRepository,
            TrackDraftTransactionService trackDraftTransactionService
    ) {
        this.trackRepository = trackRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.trackDraftTransactionService = trackDraftTransactionService;
    }

    public TrackDraftResponse updateDraft(Long trackId, TrackDraftRequest request, int userId) {
        TrackEntity track = findTrack(trackId);
        if (track.getStatus() != TrackStatus.DRAFT) {
            throw new TrackNotDraftException(trackId);
        }
        if (!Objects.equals(request.getVersion(), track.getVersion())) {
            throw new TrackVersionConflictException(request.getVersion(), track.getVersion());
        }

        String title = request.hasTitle()
                ? requiredText(request.getTitle(), "title")
                : track.getTitle();
        String artistName = request.hasArtistName()
                ? requiredText(request.getArtistName(), "artistName")
                : track.getArtistName();
        String albumName = request.hasAlbumName()
                ? optionalText(request.getAlbumName())
                : track.getAlbumName();
        Integer trackNumber = request.hasTrackNumber()
                ? positiveNumber(request.getTrackNumber(), "trackNumber")
                : track.getTrackNumber();
        Integer discNumber = request.hasDiscNumber()
                ? positiveNumber(request.getDiscNumber(), "discNumber")
                : track.getDiscNumber();

        try {
            TrackEntity updated = trackDraftTransactionService.updateDraft(
                    track,
                    title,
                    artistName,
                    albumName,
                    trackNumber,
                    discNumber,
                    userId
            );
            return response(updated);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new TrackVersionConflictException(request.getVersion(), track.getVersion());
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track draft could not be updated", exception);
        }
    }

    private TrackEntity findTrack(Long trackId) {
        try {
            return trackRepository.findById(trackId)
                    .orElseThrow(() -> new TrackNotFoundException(trackId));
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track could not be read", exception);
        }
    }

    private TrackDraftResponse response(TrackEntity track) {
        try {
            MediaAssetEntity audioAsset = mediaAssetRepository.findById(track.getAudioAssetId())
                    .orElseThrow(() -> new TrackStoreUnavailableException(
                            "Track audio asset was not found",
                            new IllegalStateException("Missing audio asset")
                    ));
            MediaAssetEntity coverAsset = track.getCoverAssetId() == null
                    ? null
                    : mediaAssetRepository.findById(track.getCoverAssetId()).orElseThrow(
                            () -> new TrackStoreUnavailableException(
                                    "Track cover asset was not found",
                                    new IllegalStateException("Missing cover asset")
                            )
                    );
            return TrackDraftResponseMapper.from(track, audioAsset, coverAsset);
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track media assets could not be read", exception);
        }
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new TrackDraftValidationException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new TrackDraftValidationException(field + " must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
        return normalized;
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : requiredText(value, "albumName");
    }

    private Integer positiveNumber(Integer value, String field) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new TrackDraftValidationException(field + " must be greater than zero");
        }
        return value;
    }
}
