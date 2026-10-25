package org.example.music.track.exception;

public class MediaObjectCleanupFailedException extends RuntimeException {

    public MediaObjectCleanupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
