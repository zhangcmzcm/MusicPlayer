package org.example.music.track.exception;

import java.util.UUID;

public class ImportConflictException extends RuntimeException {

    private final UUID uploadId;

    public ImportConflictException(UUID uploadId, String message) {
        super(message);
        this.uploadId = uploadId;
    }

    public UUID getUploadId() {
        return uploadId;
    }
}
