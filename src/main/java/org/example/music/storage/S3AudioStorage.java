package org.example.music.storage;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioObjectRange;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.storage.exception.InvalidAudioRangeException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3AudioStorage implements AudioStorage {

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    private final S3Client s3Client;

    public S3AudioStorage(S3Client s3Client) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    }

    @Override
    public AudioObjectMetadata put(
            String bucket,
            String objectKey,
            java.io.InputStream content,
            long contentLength,
            String contentType,
            String sha256
    ) {
        requireObjectLocation(bucket, objectKey);
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        String normalizedSha256 = normalizeSha256(sha256);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(contentType)
                            .metadata(Map.of("sha256", normalizedSha256))
                            .build(),
                    RequestBody.fromInputStream(content, contentLength)
            );
            return new AudioObjectMetadata(
                    bucket,
                    objectKey,
                    contentType,
                    contentLength,
                    normalizedSha256
            );
        } catch (S3Exception exception) {
            throw new AudioStorageUnavailableException("Audio object upload failed", exception);
        } catch (SdkException exception) {
            throw new AudioStorageUnavailableException("Audio object upload failed", exception);
        }
    }

    @Override
    public AudioObjectMetadata head(String bucket, String objectKey) {
        requireObjectLocation(bucket, objectKey);

        try {
            HeadObjectResponse response = s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build()
            );
            String sha256 = normalizeSha256(response.metadata().get("sha256"));
            return new AudioObjectMetadata(
                    bucket,
                    objectKey,
                    response.contentType(),
                    response.contentLength(),
                    sha256
            );
        } catch (NoSuchKeyException | NoSuchBucketException exception) {
            throw new AudioObjectNotFoundException(bucket, objectKey);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new AudioObjectNotFoundException(bucket, objectKey);
            }
            throw new AudioStorageUnavailableException("Audio object metadata read failed", exception);
        } catch (SdkException exception) {
            throw new AudioStorageUnavailableException("Audio object metadata read failed", exception);
        }
    }

    @Override
    public AudioObjectRange read(String bucket, String objectKey) {
        AudioObjectMetadata metadata = head(bucket, objectKey);
        return readRange(metadata, 0, metadata.sizeBytes() - 1);
    }

    @Override
    public AudioObjectRange readRange(String bucket, String objectKey, long rangeStart, long rangeEnd) {
        return readRange(head(bucket, objectKey), rangeStart, rangeEnd);
    }

    @Override
    public void delete(String bucket, String objectKey) {
        requireObjectLocation(bucket, objectKey);

        try {
            s3Client.deleteObject(builder -> builder.bucket(bucket).key(objectKey));
        } catch (NoSuchKeyException | NoSuchBucketException exception) {
            return;
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return;
            }
            throw new AudioStorageUnavailableException("Audio object deletion failed", exception);
        } catch (SdkException exception) {
            throw new AudioStorageUnavailableException("Audio object deletion failed", exception);
        }
    }

    private AudioObjectRange readRange(AudioObjectMetadata metadata, long rangeStart, long rangeEnd) {
        if (metadata.sizeBytes() <= 0
                || rangeStart < 0
                || rangeEnd < rangeStart
                || rangeStart >= metadata.sizeBytes()
                || rangeEnd >= metadata.sizeBytes()) {
            throw new InvalidAudioRangeException(rangeStart, rangeEnd);
        }

        try {
            var response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(metadata.bucket())
                    .key(metadata.objectKey())
                    .range("bytes=" + rangeStart + "-" + rangeEnd)
                    .build());

            return new AudioObjectRange(
                    response,
                    metadata.contentType(),
                    rangeEnd - rangeStart + 1,
                    rangeStart,
                    rangeEnd,
                    metadata.sizeBytes(),
                    metadata.sha256()
            );
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new AudioObjectNotFoundException(metadata.bucket(), metadata.objectKey());
            }
            throw new AudioStorageUnavailableException("Audio object range read failed", exception);
        } catch (SdkException exception) {
            throw new AudioStorageUnavailableException("Audio object range read failed", exception);
        }
    }

    private String normalizeSha256(String sha256) {
        if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be a 64-character hexadecimal value");
        }
        return sha256.toLowerCase(Locale.ROOT);
    }

    private void requireObjectLocation(String bucket, String objectKey) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }
}
