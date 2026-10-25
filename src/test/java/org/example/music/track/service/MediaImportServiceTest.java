package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.example.music.storage.config.StorageProperties;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.domain.AudioFileFormat;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.MediaImportEntity;
import org.example.music.track.domain.MediaImportStatus;
import org.example.music.track.domain.MetadataWarning;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.exception.ImportConflictException;
import org.example.music.track.exception.MediaImportStoreUnavailableException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.MediaImportRepository;
import org.example.music.track.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;

class MediaImportServiceTest {

    private static final UUID UPLOAD_ID = UUID.fromString("36bd81f4-2fa2-4d1c-a7c3-b6423690a3fa");

    @TempDir
    Path tempDir;

    private MediaImportRepository mediaImportRepository;
    private MediaAssetRepository mediaAssetRepository;
    private TrackRepository trackRepository;
    private AudioMetadataReader audioMetadataReader;
    private AudioStorage audioStorage;
    private TrackImportTransactionService trackImportTransactionService;
    private MediaImportService service;

    @BeforeEach
    void setUp() {
        mediaImportRepository = mock(MediaImportRepository.class);
        mediaAssetRepository = mock(MediaAssetRepository.class);
        trackRepository = mock(TrackRepository.class);
        audioMetadataReader = mock(AudioMetadataReader.class);
        audioStorage = mock(AudioStorage.class);
        trackImportTransactionService = mock(TrackImportTransactionService.class);

        TrackImportProperties properties = new TrackImportProperties(
                tempDir.toString(),
                200L * 1024 * 1024,
                20L * 1024 * 1024,
                10
        );
        StorageProperties storageProperties = new StorageProperties(
                "http://localhost:9000",
                "us-east-1",
                "access",
                "secret",
                "music-audio",
                "music-covers",
                java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(30)
        );
        service = new MediaImportService(
                mediaImportRepository,
                mediaAssetRepository,
                trackRepository,
                new AudioFileValidator(properties),
                new TemporaryImportFileStore(properties),
                audioMetadataReader,
                audioStorage,
                storageProperties,
                properties,
                trackImportTransactionService,
                new ObjectMapper()
        );
    }

    @Test
    void should_return_existing_result_when_upload_id_and_content_match() throws Exception {
        MockMultipartFile file = mp3File("minimal.mp3");
        MediaImportEntity importEntity = readyImport(sha256(file.getBytes()));
        when(mediaImportRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(importEntity));
        when(trackRepository.findById(1L)).thenReturn(Optional.of(track(1L, 2L)));
        when(mediaAssetRepository.findById(2L)).thenReturn(Optional.of(audioAsset(2L)));

        TrackImportResult result = service.importTrack(UPLOAD_ID, file, 7);

        assertFalse(result.created());
        assertEquals(importEntity, result.importEntity());
    }

    @Test
    void should_throw_conflict_when_upload_id_is_reused_for_different_content() throws Exception {
        MediaImportEntity importEntity = readyImport("0".repeat(64));
        when(mediaImportRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.of(importEntity));

        assertThrows(
                ImportConflictException.class,
                () -> service.importTrack(UPLOAD_ID, mp3File("other.mp3"), 7)
        );
    }

    @Test
    void should_store_failed_import_and_delete_object_when_database_transaction_fails() throws Exception {
        MockMultipartFile file = mp3File("minimal.mp3");
        MediaImportEntity importEntity = MediaImportEntity.start(
                UPLOAD_ID,
                "minimal.mp3",
                "audio/mpeg",
                LocalDateTime.now(java.time.ZoneOffset.UTC),
                7
        );
        when(mediaImportRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(mediaImportRepository.saveAndFlush(any(MediaImportEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(audioMetadataReader.extract(any(), eq(AudioFileFormat.MP3), any()))
                .thenReturn(new AudioMetadataResult(metadata(), Optional.empty(), false));
        when(trackImportTransactionService.writeMetadataJson(any())).thenReturn("{}");
        when(audioStorage.put(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(new AudioObjectMetadata("music-audio", "audio/key.mp3", "audio/mpeg", 10L, "a".repeat(64)));
        when(trackImportTransactionService.complete(
                any(),
                any(),
                any(),
                any(),
                any(),
                anyInt(),
                any()
        )).thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThrows(
                MediaImportStoreUnavailableException.class,
                () -> service.importTrack(UPLOAD_ID, file, 7)
        );

        ArgumentCaptor<MediaImportEntity> importCaptor = ArgumentCaptor.forClass(MediaImportEntity.class);
        verify(mediaImportRepository, org.mockito.Mockito.atLeastOnce()).saveAndFlush(importCaptor.capture());
        MediaImportEntity savedImport = importCaptor.getAllValues().get(importCaptor.getAllValues().size() - 1);
        assertEquals(MediaImportStatus.FAILED, savedImport.getStatus());
        assertEquals("MEDIA_IMPORT_STORE_UNAVAILABLE", savedImport.getFailureCode());
        verify(audioStorage).delete("music-audio", "audio/key.mp3");
    }

    @Test
    void should_apply_metadata_fallbacks_when_audio_tags_are_missing() throws Exception {
        MockMultipartFile file = mp3File("fallback-name.mp3");
        when(mediaImportRepository.findByUploadId(UPLOAD_ID)).thenReturn(Optional.empty());
        when(mediaImportRepository.saveAndFlush(any(MediaImportEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(audioMetadataReader.extract(any(), eq(AudioFileFormat.MP3), any()))
                .thenReturn(new AudioMetadataResult(metadata(), Optional.empty(), false));
        when(trackImportTransactionService.writeMetadataJson(any())).thenReturn("{}");
        when(audioStorage.put(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(new AudioObjectMetadata("music-audio", "audio/key.mp3", "audio/mpeg", 10L, "a".repeat(64)));
        when(trackImportTransactionService.complete(any(), any(), any(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> new TrackImportResult(
                        invocation.getArgument(0),
                        track(3L, 4L),
                        audioAsset(4L),
                        null,
                        Set.of(
                                MetadataWarning.TITLE_FALLBACK,
                                MetadataWarning.ARTIST_FALLBACK,
                                MetadataWarning.COVER_MISSING,
                                MetadataWarning.TRACK_NUMBER_INVALID,
                                MetadataWarning.DISC_NUMBER_INVALID
                        ),
                        true
                ));

        service.importTrack(UPLOAD_ID, file, 7);

        ArgumentCaptor<PreparedTrackData> captor = ArgumentCaptor.forClass(PreparedTrackData.class);
        verify(trackImportTransactionService).complete(
                any(),
                captor.capture(),
                any(),
                any(),
                any(),
                anyInt(),
                any()
        );
        PreparedTrackData data = captor.getValue();
        assertEquals("fallback-name", data.title());
        assertEquals("Unknown Artist", data.artistName());
        assertTrue(data.warnings().contains(MetadataWarning.TITLE_FALLBACK));
        assertTrue(data.warnings().contains(MetadataWarning.ARTIST_FALLBACK));
        assertTrue(data.warnings().contains(MetadataWarning.COVER_MISSING));
    }

    private MockMultipartFile mp3File(String filename) throws Exception {
        byte[] content = Files.readAllBytes(AudioTestFixtures.minimalMp3(tempDir));
        return new MockMultipartFile("file", filename, "audio/mpeg", content);
    }

    private MediaImportEntity readyImport(String sha256) {
        MediaImportEntity importEntity = MediaImportEntity.start(
                UPLOAD_ID,
                "minimal.mp3",
                "audio/mpeg",
                LocalDateTime.now(java.time.ZoneOffset.UTC),
                7
        );
        importEntity.uploaded(10, sha256, LocalDateTime.now(java.time.ZoneOffset.UTC));
        importEntity.ready(1L, LocalDateTime.now(java.time.ZoneOffset.UTC));
        return importEntity;
    }

    private AudioMetadata metadata() {
        return new AudioMetadata(
                AudioFileFormat.MP3,
                null,
                null,
                null,
                1000L,
                null,
                null,
                Map.of("durationMs", 1000L)
        );
    }

    private TrackEntity track(Long trackId, Long audioAssetId) {
        return TrackEntity.createDraft(
                "fallback-name",
                "Unknown Artist",
                null,
                1000L,
                null,
                null,
                audioAssetId,
                null,
                "{}",
                LocalDateTime.now(java.time.ZoneOffset.UTC),
                7
        );
    }

    private MediaAssetEntity audioAsset(Long id) {
        return MediaAssetEntity.create(
                MediaAssetKind.AUDIO,
                "music-audio",
                "audio/key.mp3",
                "audio/mpeg",
                "minimal.mp3",
                10L,
                "a".repeat(64),
                LocalDateTime.now(java.time.ZoneOffset.UTC),
                7
        );
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }
}
