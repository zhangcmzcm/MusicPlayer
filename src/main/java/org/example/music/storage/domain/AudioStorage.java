package org.example.music.storage.domain;

import java.io.InputStream;

public interface AudioStorage {

    AudioObjectMetadata put(
            String bucket,
            String objectKey,
            InputStream content,
            long contentLength,
            String contentType,
            String sha256
    );

    AudioObjectMetadata head(String bucket, String objectKey);

    AudioObjectRange read(String bucket, String objectKey);

    AudioObjectRange readRange(String bucket, String objectKey, long rangeStart, long rangeEnd);

    void delete(String bucket, String objectKey);
}
