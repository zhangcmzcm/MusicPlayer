package org.example.music.track.exception;

public class TrackDeleteFailedException extends RuntimeException {

    public TrackDeleteFailedException(Long trackId, Throwable cause) {
        super("Track delete failed: " + trackId, cause);
    }
}