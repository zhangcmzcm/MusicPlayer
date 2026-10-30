package org.example.music.storage.exception;

public class AudioStreamInconsistentException extends RuntimeException {
    public AudioStreamInconsistentException() { super("Audio storage response is inconsistent"); }
}
