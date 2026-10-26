package org.example.music.track.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.repository.TrackRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackDraftTransactionService {

    private final TrackRepository trackRepository;

    public TrackDraftTransactionService(TrackRepository trackRepository) {
        this.trackRepository = trackRepository;
    }

    @Transactional
    public TrackEntity updateDraft(
            TrackEntity track,
            String title,
            String artistName,
            String albumName,
            Integer trackNumber,
            Integer discNumber,
            int userId
    ) {
        track.updateDraft(
                title,
                artistName,
                albumName,
                trackNumber,
                discNumber,
                LocalDateTime.now(ZoneOffset.UTC),
                userId
        );
        return trackRepository.saveAndFlush(track);
    }
}
