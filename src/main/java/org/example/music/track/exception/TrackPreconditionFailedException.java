package org.example.music.track.exception;

public class TrackPreconditionFailedException extends RuntimeException {
    public TrackPreconditionFailedException() { super("Audio precondition failed"); }
}
