package org.example.music.track.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.storage.config.StorageProperties;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.domain.AudioFileFormat;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaImportEntity;
import org.example.music.track.domain.MediaImportStatus;
import org.example.music.track.domain.MetadataWarning;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.exception.AudioMetadataInvalidException;
import org.example.music.track.exception.ImportConflictException;
import org.example.music.track.exception.ImportInProgressException;
import org.example.music.track.exception.MediaImportStoreUnavailableException;
import org.example.music.track.exception.MediaObjectCleanupFailedException;
import org.example.music.track.exception.UnsupportedAudioFormatException;
import org.example.music.track.exception.UploadTooLargeException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.MediaImportRepository;
import org.example.music.track.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaImportService.class);
    private static final int MAX_TEXT_LENGTH = 255;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final MediaImportRepository mediaImportRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final TrackRepository trackRepository;
    private final AudioFileValidator audioFileValidator;
    private final TemporaryImportFileStore temporaryImportFileStore;
    private final AudioMetadataReader audioMetadataReader;
    private final AudioStorage audioStorage;
    private final StorageProperties storageProperties;
    private final TrackImportProperties trackImportProperties;
    private final TrackImportTransactionService trackImportTransactionService;
    private final ObjectMapper objectMapper;

    public MediaImportService(
            MediaImportRepository mediaImportRepository,
            MediaAssetRepository mediaAssetRepository,
            TrackRepository trackRepository,
            AudioFileValidator audioFileValidator,
            TemporaryImportFileStore temporaryImportFileStore,
            AudioMetadataReader audioMetadataReader,
            AudioStorage audioStorage,
            StorageProperties storageProperties,
            TrackImportProperties trackImportProperties,
            TrackImportTransactionService trackImportTransactionService,
            ObjectMapper objectMapper
    ) {
        this.mediaImportRepository = mediaImportRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.trackRepository = trackRepository;
        this.audioFileValidator = audioFileValidator;
        this.temporaryImportFileStore = temporaryImportFileStore;
        this.audioMetadataReader = audioMetadataReader;
        this.audioStorage = audioStorage;
        this.storageProperties = storageProperties;
        this.trackImportProperties = trackImportProperties;
        this.trackImportTransactionService = trackImportTransactionService;
        this.objectMapper = objectMapper;
    }

    public TrackImportResult importTrack(UUID uploadId, MultipartFile file, int userId) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedAudioFormatException("Audio file is required");
        }
        String originalFilename = safeFilename(file.getOriginalFilename());
        String contentType = file.getContentType();

        Optional<MediaImportEntity> existing = findByUploadId(uploadId);
        if (existing.isPresent() && existing.get().getStatus() == MediaImportStatus.READY) {
            AudioFileFormat format = audioFileValidator.validateRequest(file);
            return processExistingReady(existing.get(), file, originalFilename, format);
        }
        if (existing.isPresent()
                && existing.get().getStatus() != MediaImportStatus.FAILED) {
            throw new ImportInProgressException(uploadId);
        }

        String previousSha256 = existing.map(MediaImportEntity::getSha256).orElse(null);
        String previousFilename = existing.map(MediaImportEntity::getOriginalFilename).orElse(null);
        MediaImportEntity importEntity = existing.orElseGet(() -> startImport(
                uploadId,
                originalFilename,
                contentType,
                userId
        ));
        if (existing.isPresent()) {
            retryImport(importEntity, originalFilename, contentType);
        }

        List<AudioObjectMetadata> uploadedObjects = new ArrayList<>();
        try (TemporaryImportFileStore.Workspace workspace = temporaryImportFileStore.createWorkspace()) {
            AudioFileFormat format = audioFileValidator.validateRequest(file);
            Path audioFile = workspace.createAudioFile(format.extension());
            HashedFile hashedFile = copyAndHash(file, audioFile);
            audioFileValidator.validateFile(audioFile, format, contentType);
            if (previousSha256 != null && !(previousSha256.equalsIgnoreCase(hashedFile.sha256())
                    && previousFilename.equals(originalFilename))) {
                throw new ImportConflictException(uploadId, "uploadId is already used by another file");
            }
            markUploaded(importEntity, hashedFile.sizeBytes(), hashedFile.sha256());

            Path coverFile = workspace.createCoverFile("img");
            AudioMetadataResult metadataResult = audioMetadataReader.extract(audioFile, format, coverFile);
            PreparedTrackData trackData = prepareTrackData(
                    metadataResult.metadata(),
                    originalFilename,
                    metadataResult.artworkTooLarge(),
                    metadataResult.artwork().isPresent()
            );
            markExtracting(importEntity);

            AudioObjectMetadata audioObject = uploadAudio(
                    uploadId,
                    format,
                    audioFile,
                    hashedFile,
                    originalFilename,
                    userId
            );
            uploadedObjects.add(audioObject);

            AudioObjectMetadata coverObject = null;
            if (metadataResult.artwork().isPresent()) {
                ExtractedArtwork artwork = metadataResult.artwork().orElseThrow();
                coverObject = uploadCover(uploadId, artwork, originalFilename, userId);
                uploadedObjects.add(coverObject);
            }

            return trackImportTransactionService.complete(
                    importEntity,
                    trackData,
                    audioObject,
                    coverObject,
                    originalFilename,
                    userId,
                    now()
            );
        } catch (Exception exception) {
            RuntimeException failure = runtimeFailure(exception);
            try {
                cleanupObjects(uploadedObjects);
            } catch (RuntimeException cleanupFailure) {
                markFailedSafely(importEntity, "MEDIA_OBJECT_CLEANUP_FAILED", safeFailureReason(cleanupFailure));
                throw cleanupFailure;
            }
            markFailedSafely(importEntity, failureCode(failure), safeFailureReason(failure));
            throw failure;
        }
    }

    private RuntimeException runtimeFailure(Exception exception) {
        if (exception instanceof DataAccessException) {
            return new MediaImportStoreUnavailableException("Media import state could not be saved", exception);
        }
        return exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new MediaImportStoreUnavailableException("Track import failed", exception);
    }

    private TrackImportResult processExistingReady(
            MediaImportEntity importEntity,
            MultipartFile file,
            String originalFilename,
            AudioFileFormat format
    ) {
        try (TemporaryImportFileStore.Workspace workspace = temporaryImportFileStore.createWorkspace()) {
            Path audioFile = workspace.createAudioFile(format.extension());
            HashedFile hashedFile = copyAndHash(file, audioFile);
            if (!matches(importEntity, originalFilename, hashedFile.sha256())) {
                throw new ImportConflictException(importEntity.getUploadId(), "uploadId is already used by another file");
            }
            return loadResult(importEntity);
        } catch (IOException exception) {
            throw new MediaImportStoreUnavailableException("Temporary import file could not be created", exception);
        }
    }

    private MediaImportEntity startImport(
            UUID uploadId,
            String originalFilename,
            String contentType,
            int userId
    ) {
        try {
            return mediaImportRepository.saveAndFlush(MediaImportEntity.start(
                    uploadId,
                    originalFilename,
                    normalizeContentType(contentType),
                    now(),
                    userId
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new ImportInProgressException(uploadId);
        } catch (DataAccessException exception) {
            throw new MediaImportStoreUnavailableException("Media import state could not be created", exception);
        }
    }

    private void retryImport(MediaImportEntity importEntity, String originalFilename, String contentType) {
        try {
            importEntity.retry(originalFilename, normalizeContentType(contentType), now());
            mediaImportRepository.saveAndFlush(importEntity);
        } catch (DataAccessException exception) {
            throw new MediaImportStoreUnavailableException("Media import state could not be reset", exception);
        }
    }

    private void markUploaded(MediaImportEntity importEntity, long sizeBytes, String sha256) {
        try {
            importEntity.uploaded(sizeBytes, sha256, now());
            mediaImportRepository.saveAndFlush(importEntity);
        } catch (DataAccessException exception) {
            throw new MediaImportStoreUnavailableException("Media import state could not be updated", exception);
        }
    }

    private void markExtracting(MediaImportEntity importEntity) {
        try {
            importEntity.extracting(now());
            mediaImportRepository.saveAndFlush(importEntity);
        } catch (DataAccessException exception) {
            throw new MediaImportStoreUnavailableException("Media import state could not be updated", exception);
        }
    }

    private void markFailedSafely(MediaImportEntity importEntity, String failureCode, String failureReason) {
        try {
            importEntity.failed(failureCode, failureReason, now());
            mediaImportRepository.saveAndFlush(importEntity);
        } catch (DataAccessException exception) {
            LOGGER.warn(
                    "Failed to persist media import failure, importId={}, failureCode={}",
                    importEntity.getId(),
                    failureCode,
                    exception
            );
        }
    }

    private HashedFile copyAndHash(MultipartFile file, Path target) {
        try (InputStream input = file.getInputStream();
             OutputStream fileOutput = Files.newOutputStream(target);
             DigestOutputStream output = new DigestOutputStream(fileOutput, sha256Digest())) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > trackImportProperties.audioMaxBytes()) {
                    throw new UploadTooLargeException(trackImportProperties.audioMaxBytes());
                }
                output.write(buffer, 0, read);
            }
            output.flush();
            return new HashedFile(total, hex(output.getMessageDigest().digest()));
        } catch (UploadTooLargeException exception) {
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new UnsupportedAudioFormatException("Audio file could not be stored for validation");
        }
    }

    private AudioObjectMetadata uploadAudio(
            UUID uploadId,
            AudioFileFormat format,
            Path audioFile,
            HashedFile hashedFile,
            String originalFilename,
            int userId
    ) {
        String objectKey = "audio/" + uploadId + "/" + hashedFile.sha256() + "." + format.extension();
        try (InputStream input = Files.newInputStream(audioFile)) {
            return audioStorage.put(
                    storageProperties.audioBucket(),
                    objectKey,
                    input,
                    hashedFile.sizeBytes(),
                    format.contentType(),
                    hashedFile.sha256()
            );
        } catch (IOException exception) {
            throw new MediaObjectCleanupFailedException("Audio file could not be opened for MinIO upload", exception);
        }
    }

    private AudioObjectMetadata uploadCover(
            UUID uploadId,
            ExtractedArtwork artwork,
            String originalFilename,
            int userId
    ) {
        String extension = coverExtension(artwork.contentType());
        String sha256 = sha256(artwork.path());
        String objectKey = "covers/" + uploadId + "/" + sha256 + "." + extension;
        try (InputStream input = Files.newInputStream(artwork.path())) {
            return audioStorage.put(
                    storageProperties.coversBucket(),
                    objectKey,
                    input,
                    artwork.sizeBytes(),
                    artwork.contentType(),
                    sha256
            );
        } catch (IOException exception) {
            throw new MediaObjectCleanupFailedException("Cover file could not be opened for MinIO upload", exception);
        }
    }

    private PreparedTrackData prepareTrackData(
            AudioMetadata metadata,
            String originalFilename,
            boolean artworkTooLarge,
            boolean artworkPresent
    ) {
        Set<MetadataWarning> warnings = EnumSet.noneOf(MetadataWarning.class);
        String title = normalizedText(metadata.rawTitle(), MAX_TEXT_LENGTH);
        if (title == null) {
            title = filenameWithoutExtension(originalFilename);
            warnings.add(MetadataWarning.TITLE_FALLBACK);
        } else if (metadata.rawTitle().trim().length() > MAX_TEXT_LENGTH) {
            warnings.add(MetadataWarning.TITLE_TRUNCATED);
        }

        String artistName = normalizedText(metadata.rawArtistName(), MAX_TEXT_LENGTH);
        if (artistName == null) {
            artistName = "Unknown Artist";
            warnings.add(MetadataWarning.ARTIST_FALLBACK);
        } else if (metadata.rawArtistName().trim().length() > MAX_TEXT_LENGTH) {
            warnings.add(MetadataWarning.ARTIST_TRUNCATED);
        }

        String albumName = normalizedText(metadata.rawAlbumName(), MAX_TEXT_LENGTH);
        if (albumName != null && metadata.rawAlbumName().trim().length() > MAX_TEXT_LENGTH) {
            warnings.add(MetadataWarning.ALBUM_TRUNCATED);
        }

        if (metadata.rawTrackNumber() == null && metadata.rawMetadata().get("trackNumber") == null) {
            warnings.add(MetadataWarning.TRACK_NUMBER_INVALID);
        }
        if (metadata.rawDiscNumber() == null && metadata.rawMetadata().get("discNumber") == null) {
            warnings.add(MetadataWarning.DISC_NUMBER_INVALID);
        }
        if (!artworkPresent && !artworkTooLarge) {
            warnings.add(MetadataWarning.COVER_MISSING);
        }
        if (artworkTooLarge) {
            warnings.add(MetadataWarning.COVER_TOO_LARGE);
        }

        Map<String, Object> rawMetadata = new LinkedHashMap<>(metadata.rawMetadata());
        rawMetadata.put("metadataWarnings", warnings.stream().map(Enum::name).toList());
        String id3MetadataJson = trackImportTransactionService.writeMetadataJson(rawMetadata);
        return new PreparedTrackData(
                title,
                artistName,
                albumName,
                metadata.durationMs(),
                metadata.rawTrackNumber(),
                metadata.rawDiscNumber(),
                id3MetadataJson,
                warnings
        );
    }

    private TrackImportResult loadResult(MediaImportEntity importEntity) {
        if (importEntity.getTrackId() == null) {
            throw new MediaImportStoreUnavailableException(
                    "Media import is READY without a track",
                    new IllegalStateException("Missing trackId")
            );
        }
        TrackEntity track = trackRepository.findById(importEntity.getTrackId())
                .orElseThrow(() -> new MediaImportStoreUnavailableException(
                        "Imported track was not found",
                        new IllegalStateException("Missing track")
                ));
        MediaAssetEntity audioAsset = mediaAssetRepository.findById(track.getAudioAssetId())
                .orElseThrow(() -> new MediaImportStoreUnavailableException(
                        "Imported audio asset was not found",
                        new IllegalStateException("Missing audio asset")
                ));
        MediaAssetEntity coverAsset = track.getCoverAssetId() == null
                ? null
                : mediaAssetRepository.findById(track.getCoverAssetId()).orElse(null);
        return new TrackImportResult(
                importEntity,
                track,
                audioAsset,
                coverAsset,
                warningsFromMetadata(track.getId3Metadata()),
                false
        );
    }

    private Set<MetadataWarning> warningsFromMetadata(String json) {
        Set<MetadataWarning> warnings = EnumSet.noneOf(MetadataWarning.class);
        if (json == null || json.isBlank()) {
            return warnings;
        }
        try {
            JsonNode values = objectMapper.readTree(json).path("metadataWarnings");
            if (values.isArray()) {
                for (JsonNode value : values) {
                    try {
                        warnings.add(MetadataWarning.valueOf(value.asText()));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown warning values are ignored so old imports remain readable.
                    }
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Imported metadata warnings could not be parsed", exception);
        }
        return warnings;
    }

    private Optional<MediaImportEntity> findByUploadId(UUID uploadId) {
        try {
            return mediaImportRepository.findByUploadId(uploadId);
        } catch (DataAccessException exception) {
            throw new MediaImportStoreUnavailableException("Media import state could not be read", exception);
        }
    }

    private void cleanupObjects(List<AudioObjectMetadata> objects) {
        RuntimeException firstFailure = null;
        for (AudioObjectMetadata object : objects) {
            try {
                audioStorage.delete(object.bucket(), object.objectKey());
            } catch (RuntimeException exception) {
                LOGGER.warn(
                        "Media object cleanup failed, bucket={}, objectKey={}",
                        object.bucket(),
                        object.objectKey(),
                        exception
                );
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw new MediaObjectCleanupFailedException("Media object cleanup failed", firstFailure);
        }
    }

    private boolean matches(MediaImportEntity importEntity, String originalFilename, String sha256) {
        return importEntity.getSha256() != null
                && importEntity.getSha256().equalsIgnoreCase(sha256)
                && importEntity.getOriginalFilename().equals(originalFilename);
    }

    private String safeFilename(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        value = slash >= 0 ? value.substring(slash + 1) : value;
        value = value.trim();
        if (value.length() > 255) {
            value = value.substring(value.length() - 255);
        }
        return value;
    }

    private String filenameWithoutExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String value = dot > 0 ? filename.substring(0, dot) : filename;
        value = value.trim();
        return value.isEmpty() ? "Untitled" : value;
    }

    private String normalizedText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private String coverExtension(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path);
             DigestOutputStream output = new DigestOutputStream(OutputStream.nullOutputStream(), sha256Digest())) {
            input.transferTo(output);
            return hex(output.getMessageDigest().digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new AudioMetadataInvalidException("Cover checksum could not be calculated", exception);
        }
    }

    private MessageDigest sha256Digest() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    private String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format(Locale.ROOT, "%02x", item));
        }
        return builder.toString();
    }

    private String failureCode(Exception exception) {
        if (exception instanceof UploadTooLargeException) {
            return "UPLOAD_TOO_LARGE";
        }
        if (exception instanceof UnsupportedAudioFormatException) {
            return "UNSUPPORTED_AUDIO_FORMAT";
        }
        if (exception instanceof AudioMetadataInvalidException) {
            return "AUDIO_METADATA_INVALID";
        }
        if (exception instanceof ImportConflictException) {
            return "IMPORT_CONFLICT";
        }
        if (exception instanceof ImportInProgressException) {
            return "IMPORT_IN_PROGRESS";
        }
        if (exception instanceof MediaObjectCleanupFailedException) {
            return "MEDIA_OBJECT_CLEANUP_FAILED";
        }
        if (exception instanceof MediaImportStoreUnavailableException) {
            return "MEDIA_IMPORT_STORE_UNAVAILABLE";
        }
        if (exception instanceof AudioStorageUnavailableException) {
            return "MEDIA_STORAGE_UNAVAILABLE";
        }
        return "IMPORT_FAILED";
    }

    private String safeFailureReason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Track import failed" : message.substring(0, Math.min(message.length(), 1000));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    private record HashedFile(long sizeBytes, String sha256) {
    }
}
