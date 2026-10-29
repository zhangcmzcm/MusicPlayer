package org.example.music.track.exception;

public class TrackMediaCleanupFailedException extends RuntimeException {

    public TrackMediaCleanupFailedException(Long trackId, Throwable cause) {
        super("Track media cleanup failed: " + trackId, cause);
    }
}