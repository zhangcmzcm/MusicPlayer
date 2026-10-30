package org.example.music.track.exception;

public class TrackRangeNotSatisfiableException extends RuntimeException {
    private final long totalSize;
    public TrackRangeNotSatisfiableException(long totalSize) {
        super("Audio range is not satisfiable");
        this.totalSize = totalSize;
    }
    public long getTotalSize() { return totalSize; }
}
