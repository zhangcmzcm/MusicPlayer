package org.example.music.track.exception;

public class TrackNotDraftException extends RuntimeException {

    public TrackNotDraftException(Long trackId) {
        super("Track is not a draft: " + trackId);
    }
}
