package org.example.music.track.service;

import java.util.Collection;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackDeleteFailedException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackDeleteTransactionService {

    private final TrackRepository trackRepository;
    private final MediaAssetRepository mediaAssetRepository;

    public TrackDeleteTransactionService(
            TrackRepository trackRepository,
            MediaAssetRepository mediaAssetRepository
    ) {
        this.trackRepository = trackRepository;
        this.mediaAssetRepository = mediaAssetRepository;
    }

    @Transactional
    public void delete(Long trackId, Collection<Long> mediaAssetIds) {
        try {
            int deletedTracks = trackRepository.deleteArchived(trackId, TrackStatus.ARCHIVED);
            if (deletedTracks == 0) {
                throw new TrackNotFoundException(trackId);
            }
            mediaAssetRepository.deleteAllById(mediaAssetIds);
        } catch (DataAccessException exception) {
            throw new TrackDeleteFailedException(trackId, exception);
        }
    }
}