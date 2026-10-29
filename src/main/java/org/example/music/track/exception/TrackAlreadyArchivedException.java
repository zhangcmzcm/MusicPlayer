package org.example.music.track.exception;

public class TrackAlreadyArchivedException extends RuntimeException {

    public TrackAlreadyArchivedException(Long trackId) {
        super("Track is already archived: " + trackId);
    }
}