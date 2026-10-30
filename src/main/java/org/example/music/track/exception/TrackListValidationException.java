package org.example.music.track.exception;

public class TrackListValidationException extends RuntimeException {
    public TrackListValidationException() {
        super("Invalid track list parameters");
    }
}
