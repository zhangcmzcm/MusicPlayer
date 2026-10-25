package org.example.music.track.exception;

public class AudioMetadataInvalidException extends RuntimeException {

    public AudioMetadataInvalidException(String message) {
        super(message);
    }

    public AudioMetadataInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
