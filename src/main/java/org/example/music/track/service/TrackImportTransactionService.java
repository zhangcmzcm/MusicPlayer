package org.example.music.track.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.MediaImportEntity;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.MediaImportRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackImportTransactionService {

    private final MediaAssetRepository mediaAssetRepository;
    private final TrackRepository trackRepository;
    private final MediaImportRepository mediaImportRepository;
    private final ObjectMapper objectMapper;

    public TrackImportTransactionService(
            MediaAssetRepository mediaAssetRepository,
            TrackRepository trackRepository,
            MediaImportRepository mediaImportRepository,
            ObjectMapper objectMapper
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.trackRepository = trackRepository;
        this.mediaImportRepository = mediaImportRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TrackImportResult complete(
            MediaImportEntity importEntity,
            PreparedTrackData trackData,
            AudioObjectMetadata audioObject,
            AudioObjectMetadata coverObject,
            String originalFilename,
            int userId,
            LocalDateTime now
    ) {
        MediaAssetEntity audioAsset = mediaAssetRepository.save(MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                audioObject.bucket(),
                audioObject.objectKey(),
                audioObject.contentType(),
                originalFilename,
                audioObject.sizeBytes(),
                audioObject.sha256(),
                now,
                userId
        ));

        MediaAssetEntity coverAsset = null;
        if (coverObject != null) {
            coverAsset = mediaAssetRepository.save(MediaAssetEntity.create(
                    MediaAssetKind.COVER,
                    coverObject.bucket(),
                    coverObject.objectKey(),
                    coverObject.contentType(),
                    originalFilename,
                    coverObject.sizeBytes(),
                    coverObject.sha256(),
                    now,
                    userId
            ));
        }

        TrackEntity track = trackRepository.save(TrackEntity.createDraft(
                trackData.title(),
                trackData.artistName(),
                trackData.albumName(),
                trackData.durationMs(),
                trackData.trackNumber(),
                trackData.discNumber(),
                audioAsset.getId(),
                coverAsset == null ? null : coverAsset.getId(),
                trackData.id3MetadataJson(),
                now,
                userId
        ));

        importEntity.ready(track.getId(), now);
        mediaImportRepository.save(importEntity);

        return new TrackImportResult(
                importEntity,
                track,
                audioAsset,
                coverAsset,
                trackData.warnings(),
                true
        );
    }

    public String writeMetadataJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Audio metadata could not be serialized", exception);
        }
    }
}
