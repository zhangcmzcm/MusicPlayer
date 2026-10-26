package org.example.music.track.exception;

public class TrackNotFoundException extends RuntimeException {

    public TrackNotFoundException(Long trackId) {
        super("Track not found: " + trackId);
    }
}
