package org.example.music.track.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.track.exception.TrackAudioStorageInconsistentException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class TrackStreamService {
    private static final Logger LOGGER=LoggerFactory.getLogger(TrackStreamService.class);
    private final TrackStreamQueryService query;
    private final AudioStorage storage;
    public TrackStreamService(TrackStreamQueryService query,AudioStorage storage) { this.query=query;this.storage=storage; }

    @PreAuthorize("isAuthenticated()")
    public PreparedTrackStream prepare(Long trackId,AuthenticatedUserPrincipal actor,Instant now) {
        TrackStreamSnapshot snapshot;
        try { snapshot=query.load(trackId,actor); }
        catch (DataAccessException | TransactionException exception) { throw new TrackStoreUnavailableException("Audio data unavailable",exception); }
        var expected=snapshot.metadata();
        try {
            var actual=storage.head(expected.bucket(),expected.objectKey());
            if (!Objects.equals(expected.contentType(),actual.contentType()) || expected.sizeBytes()!=actual.sizeBytes()
                    || actual.sha256()==null || !expected.sha256().equalsIgnoreCase(actual.sha256())) {
                throw new TrackAudioStorageInconsistentException();
            }
        } catch (AudioObjectNotFoundException exception) { throw missing(trackId); }
        catch (IllegalArgumentException exception) { throw new TrackAudioStorageInconsistentException(); }
        boolean reliable=!snapshot.createdAt().isAfter(now);
        var modified=(reliable?snapshot.createdAt():now).truncatedTo(ChronoUnit.SECONDS);
        return new PreparedTrackStream(snapshot,"\""+expected.sha256()+"\"",modified,reliable);
    }

    public static TrackNotFoundException missing(Long trackId) {
        LOGGER.warn("Audio object missing, trackId={}, traceId={}",trackId,MDC.get("traceId"));
        return new TrackNotFoundException(trackId);
    }
}
