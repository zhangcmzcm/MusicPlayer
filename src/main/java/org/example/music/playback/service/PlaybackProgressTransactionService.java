package org.example.music.playback.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.dto.SavePlaybackProgressResponse;
import org.example.music.playback.exception.PlaybackProgressSequenceConflictException;
import org.example.music.playback.exception.PlaybackProgressStaleSequenceException;
import org.example.music.playback.exception.PlaybackProgressValidationException;
import org.example.music.playback.repository.PlaybackProgressRepository;
import org.example.music.playback.repository.PlaybackProgressRow;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.user.domain.UserRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlaybackProgressTransactionService {
    private final PlaybackProgressRepository repository;
    private final Clock clock;

    public PlaybackProgressTransactionService(PlaybackProgressRepository repository,
            @Qualifier("authenticationClock") Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public SavePlaybackProgressResponse save(String deviceId, SavePlaybackProgressRequest input,
            AuthenticatedUserPrincipal actor) {
        var track = repository.lockTrackForShare(input.trackId())
                .orElseThrow(() -> new TrackNotFoundException(input.trackId()));
        if (actor.loginRole() != UserRole.ADMIN && track.status() != TrackStatus.PUBLISHED) {
            throw new TrackNotFoundException(input.trackId());
        }
        if (track.durationMs() != null && track.durationMs() > 0
                && input.positionSeconds() > track.durationMs() / 1000) {
            throw new PlaybackProgressValidationException();
        }
        // Upsert obtains the device row lock even when two first requests race.
        repository.insertIfAbsent(actor.userId(), deviceId, input.trackId(),
                input.positionSeconds(), input.sequence(), utcNow());
        PlaybackProgressRow saved = repository.lockProgress(actor.userId(), deviceId);
        if (input.sequence() < saved.sequence()) {
            throw new PlaybackProgressStaleSequenceException(saved.sequence());
        }
        if (input.sequence() == saved.sequence()
                && (!Objects.equals(input.trackId(), saved.trackId())
                || input.positionSeconds() != saved.positionSeconds())) {
            throw new PlaybackProgressSequenceConflictException(saved.sequence());
        }
        if (input.sequence() > saved.sequence()) {
            int updated = repository.updateIfSequenceOlder(actor.userId(), deviceId, input.trackId(),
                    input.positionSeconds(), input.sequence(), utcNow());
            if (updated != 1) {
                throw new DataIntegrityViolationException("Playback progress update lost its locked row");
            }
            saved = repository.lockProgress(actor.userId(), deviceId);
        }
        return new SavePlaybackProgressResponse(saved.deviceId(), saved.trackId(),
                saved.positionSeconds(), saved.sequence(), saved.updatedAt());
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
