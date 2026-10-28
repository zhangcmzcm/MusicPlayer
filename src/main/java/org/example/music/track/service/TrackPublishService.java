package org.example.music.track.service;

import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackPublishResponse;
import org.example.music.track.dto.TrackPublishResponseMapper;
import org.example.music.track.exception.TrackNotDraftException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.repository.TrackRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackPublishService {

    private final TrackRepository trackRepository;
    private final TrackPublishTransactionService trackPublishTransactionService;

    public TrackPublishService(
            TrackRepository trackRepository,
            TrackPublishTransactionService trackPublishTransactionService
    ) {
        this.trackRepository = trackRepository;
        this.trackPublishTransactionService = trackPublishTransactionService;
    }

    @Transactional
    public TrackPublishResponse publish(Long trackId, int userId) {
        TrackEntity track = findTrack(trackId);
        if (track.getStatus() != TrackStatus.DRAFT) {
            throw new TrackNotDraftException(trackId);
        }

        try {
            TrackEntity published = trackPublishTransactionService.publish(track, userId);
            return TrackPublishResponseMapper.from(published);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new TrackStoreUnavailableException("Track could not be published", exception);
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
