package org.example.music.track.exception;

public class TrackCoverNotFoundException extends RuntimeException {
    public TrackCoverNotFoundException() {
        super("Cover not found");
    }
}
