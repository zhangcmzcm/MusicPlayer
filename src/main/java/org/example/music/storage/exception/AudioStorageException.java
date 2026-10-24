package org.example.music.storage.exception;

public class AudioStorageException extends RuntimeException {

    public AudioStorageException(String message) {
        super(message);
    }

    public AudioStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
