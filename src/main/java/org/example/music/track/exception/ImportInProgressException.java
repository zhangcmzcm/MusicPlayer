package org.example.music.track.exception;

import java.util.UUID;

public class ImportInProgressException extends RuntimeException {

    private final UUID uploadId;

    public ImportInProgressException(UUID uploadId) {
        super("A media import is already in progress for uploadId " + uploadId);
        this.uploadId = uploadId;
    }

    public UUID getUploadId() {
        return uploadId;
    }
}
