package org.example.music.track.exception;

public class TrackVersionConflictException extends RuntimeException {

    private final Long expectedVersion;
    private final Long actualVersion;

    public TrackVersionConflictException(Long expectedVersion, Long actualVersion) {
        super("Track version conflict");
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public Long getActualVersion() {
        return actualVersion;
    }
}
