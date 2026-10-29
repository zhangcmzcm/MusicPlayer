package org.example.music.track.exception;

public class TrackNotArchivedException extends RuntimeException {

    public TrackNotArchivedException(Long trackId) {
        super("Track is not archived: " + trackId);
    }
}