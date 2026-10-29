package org.example.music.track.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackDeleteFailedException;
import org.example.music.track.exception.TrackMediaCleanupFailedException;
import org.example.music.track.exception.TrackNotArchivedException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class TrackDeleteService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackDeleteService.class);

    private final TrackRepository trackRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final AudioStorage audioStorage;
    private final TrackDeleteTransactionService trackDeleteTransactionService;

    public TrackDeleteService(
            TrackRepository trackRepository,
            MediaAssetRepository mediaAssetRepository,
            AudioStorage audioStorage,
            TrackDeleteTransactionService trackDeleteTransactionService
    ) {
        this.trackRepository = trackRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.audioStorage = audioStorage;
        this.trackDeleteTransactionService = trackDeleteTransactionService;
    }

    public void delete(Long trackId) {
        TrackEntity track = findTrack(trackId);
        if (track.getStatus() != TrackStatus.ARCHIVED) {
            throw new TrackNotArchivedException(trackId);
        }

        Set<Long> mediaAssetIds = mediaAssetIds(track);
        List<MediaAssetEntity> mediaAssets = findMediaAssets(mediaAssetIds);
        deleteObjects(trackId, mediaAssets);
        trackDeleteTransactionService.delete(trackId, mediaAssetIds);
    }

    private TrackEntity findTrack(Long trackId) {
        try {
            return trackRepository.findById(trackId)
                    .orElseThrow(() -> new TrackNotFoundException(trackId));
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track could not be read", exception);
        }
    }

    private Set<Long> mediaAssetIds(TrackEntity track) {
        Set<Long> mediaAssetIds = new LinkedHashSet<>();
        mediaAssetIds.add(track.getAudioAssetId());
        if (track.getCoverAssetId() != null) {
            mediaAssetIds.add(track.getCoverAssetId());
        }
        return mediaAssetIds;
    }

    private List<MediaAssetEntity> findMediaAssets(Set<Long> mediaAssetIds) {
        try {
            List<MediaAssetEntity> mediaAssets = mediaAssetRepository.findAllById(mediaAssetIds);
            if (mediaAssets.size() != mediaAssetIds.size()) {
                throw new TrackDeleteFailedException(
                        mediaAssetIds.iterator().next(),
                        new IllegalStateException("Track media asset metadata is incomplete")
                );
            }
            return mediaAssets;
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track media assets could not be read", exception);
        }
    }

    private void deleteObjects(Long trackId, Collection<MediaAssetEntity> mediaAssets) {
        RuntimeException firstFailure = null;
        for (MediaAssetEntity mediaAsset : mediaAssets) {
            try {
                audioStorage.delete(mediaAsset.getBucket(), mediaAsset.getObjectKey());
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Track media object cleanup failed, traceId={}, trackId={}, mediaAssetId={}, exceptionType={}",
                        MDC.get("traceId"),
                        trackId,
                        mediaAsset.getId(),
                        exception.getClass().getSimpleName(),
                        exception
                );
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw new TrackMediaCleanupFailedException(trackId, firstFailure);
        }
    }
}