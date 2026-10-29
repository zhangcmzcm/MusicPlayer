package org.example.music.track.service;

import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackArchiveResponse;
import org.example.music.track.dto.TrackArchiveResponseMapper;
import org.example.music.track.exception.TrackAlreadyArchivedException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.TrackRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackArchiveService {

    private final TrackRepository trackRepository;
    private final TrackArchiveTransactionService trackArchiveTransactionService;

    public TrackArchiveService(
            TrackRepository trackRepository,
            TrackArchiveTransactionService trackArchiveTransactionService
    ) {
        this.trackRepository = trackRepository;
        this.trackArchiveTransactionService = trackArchiveTransactionService;
    }

    @Transactional
    public TrackArchiveResponse archive(Long trackId, int userId) {
        TrackEntity track = findTrack(trackId);
        if (track.getStatus() == TrackStatus.ARCHIVED) {
            throw new TrackAlreadyArchivedException(trackId);
        }

        try {
            TrackEntity archived = trackArchiveTransactionService.archive(track, userId);
            return TrackArchiveResponseMapper.from(archived);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track could not be archived", exception);
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
}