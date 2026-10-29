package org.example.music.track.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.repository.TrackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackArchiveTransactionService {

    private final TrackRepository trackRepository;

    public TrackArchiveTransactionService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
    }

    @Transactional
    public TrackEntity archive(TrackEntity track, int userId) {
        track.archive(userId, LocalDateTime.now(ZoneOffset.UTC));
        return trackRepository.saveAndFlush(track);
    }
}