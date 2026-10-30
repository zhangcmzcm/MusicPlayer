package org.example.music.track.service;

import java.io.IOException;
import java.util.Objects;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioObjectRange;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.track.exception.TrackCoverNotFoundException;
import org.example.music.track.exception.TrackCoverStorageInconsistentException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class TrackCoverService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrackCoverService.class);
    private final TrackCoverQueryService query;
    private final AudioStorage storage;

    public TrackCoverService(TrackCoverQueryService query, AudioStorage storage) {
        this.query = query;
        this.storage = storage;
    }

    @PreAuthorize("isAuthenticated()")
    public PreparedTrackCover prepare(Long trackId, AuthenticatedUserPrincipal actor) {
        TrackCoverSnapshot snapshot;
        try {
            snapshot = query.load(trackId, actor);
        } catch (DataAccessException | TransactionException exception) {
            throw new TrackStoreUnavailableException("Cover data could not be read", exception);
        }
        try {
            var metadata = storage.head(snapshot.bucket(), snapshot.objectKey());
            validate(snapshot, metadata.contentType(), metadata.sizeBytes(), metadata.sha256());
        } catch (AudioObjectNotFoundException exception) {
            throw missing(trackId);
        } catch (IllegalArgumentException exception) {
            throw new TrackCoverStorageInconsistentException();
        }
        return new PreparedTrackCover(snapshot, "\"" + snapshot.sha256() + "\"");
    }

    public AudioObjectRange open(PreparedTrackCover cover) {
        TrackCoverSnapshot snapshot = cover.snapshot();
        AudioObjectRange stream;
        try {
            stream = storage.read(snapshot.bucket(), snapshot.objectKey());
        } catch (AudioObjectNotFoundException exception) {
            throw missing(snapshot.trackId());
        } catch (IllegalArgumentException exception) {
            throw new TrackCoverStorageInconsistentException();
        }
        try {
            validate(snapshot, stream.contentType(), stream.contentLength(), stream.sha256());
            if (stream.totalSize() != snapshot.sizeBytes() || stream.rangeStart() != 0
                    || stream.rangeEnd() != snapshot.sizeBytes() - 1) {
                throw new TrackCoverStorageInconsistentException();
            }
            return stream;
        } catch (RuntimeException exception) {
            try {
                stream.close();
            } catch (IOException closeFailure) {
                LOGGER.warn("Cover stream close failed, trackId={}, traceId={}", snapshot.trackId(), MDC.get("traceId"));
            }
            throw exception;
        }
    }

    private void validate(TrackCoverSnapshot snapshot, String type, long size, String sha256) {
        if (!Objects.equals(snapshot.contentType(), type) || snapshot.sizeBytes() != size
                || sha256 == null || !snapshot.sha256().equalsIgnoreCase(sha256)) {
            throw new TrackCoverStorageInconsistentException();
        }
    }

    private TrackCoverNotFoundException missing(Long trackId) {
        LOGGER.warn("Cover object missing, trackId={}, traceId={}", trackId, MDC.get("traceId"));
        return new TrackCoverNotFoundException();
    }
}
