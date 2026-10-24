package org.example.music.storage.exception;

public class InvalidAudioRangeException extends AudioStorageException {

    public InvalidAudioRangeException(long rangeStart, long rangeEnd) {
        super("Invalid audio byte range: " + rangeStart + "-" + rangeEnd);
    }
}
