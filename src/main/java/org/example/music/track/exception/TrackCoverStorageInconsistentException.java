package org.example.music.track.exception;

public class TrackCoverStorageInconsistentException extends RuntimeException {
    public TrackCoverStorageInconsistentException() {
        super("Cover storage metadata is inconsistent");
    }
}
