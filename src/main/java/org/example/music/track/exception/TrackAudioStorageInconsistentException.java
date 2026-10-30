package org.example.music.track.exception;

public class TrackAudioStorageInconsistentException extends RuntimeException {
    public TrackAudioStorageInconsistentException() { super("Audio storage response is inconsistent"); }
}
