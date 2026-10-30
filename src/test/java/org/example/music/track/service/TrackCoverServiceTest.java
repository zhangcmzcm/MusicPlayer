package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.anyString;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioObjectRange;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.track.exception.TrackCoverNotFoundException;
import org.example.music.track.exception.TrackCoverStorageInconsistentException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class TrackCoverServiceTest {
    private final TrackCoverQueryService query = mock(TrackCoverQueryService.class);
    private final AudioStorage storage = mock(AudioStorage.class);
    private final TrackCoverService service = new TrackCoverService(query, storage);
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.ADMIN);
    private final TrackCoverSnapshot snapshot = new TrackCoverSnapshot(42L, "private", "secret", "image/png", 4, "a".repeat(64));

    @BeforeEach
    void setup() {
        when(query.load(42L, actor)).thenReturn(snapshot);
        when(storage.head("private", "secret")).thenReturn(new AudioObjectMetadata("private", "secret", "image/png", 4, "A".repeat(64)));
    }

    @Test
    void should_prepare_without_opening_stream() {
        assertEquals("\"" + snapshot.sha256() + "\"", service.prepare(42L, actor).etag());
        verify(storage, never()).read(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "size", "sha", "invalidSha"})
    void should_reject_inconsistent_storage_before_conditional_match(String field) {
        when(storage.head("private", "secret")).thenReturn(new AudioObjectMetadata("private", "secret",
                field.equals("type") ? "image/jpeg" : "image/png", field.equals("size") ? 5 : 4,
                field.equals("sha") ? "b".repeat(64) : field.equals("invalidSha") ? null : snapshot.sha256()));
        assertThrows(TrackCoverStorageInconsistentException.class, () -> service.prepare(42L, actor));
    }

    @Test
    void should_map_invalid_remote_digest_without_leaking_location() {
        when(storage.head("private", "secret")).thenThrow(new IllegalArgumentException("secret"));
        var error = assertThrows(TrackCoverStorageInconsistentException.class, () -> service.prepare(42L, actor));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void should_map_missing_object_on_both_head_and_get() {
        when(storage.head("private", "secret")).thenThrow(new AudioObjectNotFoundException("private", "secret"));
        assertEquals("Cover not found", assertThrows(TrackCoverNotFoundException.class, () -> service.prepare(42L, actor)).getMessage());
        when(storage.read("private", "secret")).thenThrow(new AudioObjectNotFoundException("private", "secret"));
        assertThrows(TrackCoverNotFoundException.class, () -> service.open(new PreparedTrackCover(snapshot, "tag")));
    }

    @Test
    void should_propagate_storage_unavailable() {
        when(storage.head("private", "secret")).thenThrow(new AudioStorageUnavailableException("unavailable", null));
        assertThrows(AudioStorageUnavailableException.class, () -> service.prepare(42L, actor));
    }

    @Test
    void should_translate_database_and_transaction_failures() {
        when(query.load(42L, actor)).thenThrow(new DataAccessResourceFailureException("db"));
        assertThrows(TrackStoreUnavailableException.class, () -> service.prepare(42L, actor));
        doThrow(new CannotCreateTransactionException("db")).when(query).load(42L, actor);
        assertThrows(TrackStoreUnavailableException.class, () -> service.prepare(42L, actor));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "length", "total", "start", "end", "sha"})
    void should_close_opened_stream_when_metadata_changed(String field) throws Exception {
        var content = spy(new ByteArrayInputStream(new byte[4]));
        when(storage.read("private", "secret")).thenReturn(new AudioObjectRange(content,
                field.equals("type") ? "image/jpeg" : "image/png",
                field.equals("length") ? 3 : 4, field.equals("start") ? 1 : 0,
                field.equals("end") ? 2 : 3, field.equals("total") ? 5 : 4,
                field.equals("sha") ? "b".repeat(64) : snapshot.sha256()));
        assertThrows(TrackCoverStorageInconsistentException.class, () -> service.open(new PreparedTrackCover(snapshot, "tag")));
        verify(content).close();
    }

    @Test
    void should_translate_invalid_digest_from_repeated_head_on_open() {
        when(storage.read("private", "secret")).thenThrow(new IllegalArgumentException("private location"));
        assertThrows(TrackCoverStorageInconsistentException.class, () -> service.open(new PreparedTrackCover(snapshot, "tag")));
    }

    @Test
    void should_return_valid_open_stream_to_owner() throws IOException {
        var content = spy(new ByteArrayInputStream(new byte[4]));
        when(storage.read("private", "secret")).thenReturn(new AudioObjectRange(content, "image/png", 4, 0, 3, 4, snapshot.sha256()));
        try (var stream = service.open(new PreparedTrackCover(snapshot, "tag"))) {
            assertSame(content, stream.content());
            verify(content, never()).close();
        }
        verify(content).close();
    }
}
