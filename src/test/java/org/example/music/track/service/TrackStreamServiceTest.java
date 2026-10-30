package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.anyString;

import java.time.Instant;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.exception.TrackAudioStorageInconsistentException;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

class TrackStreamServiceTest {
    private final TrackStreamQueryService query=mock(TrackStreamQueryService.class);
    private final AudioStorage storage=mock(AudioStorage.class);
    private final TrackStreamService service=new TrackStreamService(query,storage);
    private final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(7,UserRole.ADMIN);
    private final AudioObjectMetadata meta=new AudioObjectMetadata("private","secret","audio/mpeg",10,"a".repeat(64));
    private final Instant now=Instant.parse("2026-10-30T00:00:00Z");
    private void setup(Instant created) { when(query.load(42L,actor)).thenReturn(new TrackStreamSnapshot(42L,meta,created));when(storage.head("private","secret")).thenReturn(meta); }
    @Test
    void should_use_asset_time_and_clamp_future_time() {
        setup(now.minusMillis(1500));var p=service.prepare(42L,actor,now);
        assertEquals(now.minusSeconds(2),p.lastModified());assertTrue(p.dateStrong());assertEquals("\""+meta.sha256()+"\"",p.etag());
        setup(now.plusSeconds(10));p=service.prepare(42L,actor,now);assertEquals(now,p.lastModified());assertFalse(p.dateStrong());
        verify(storage,never()).read(anyString(),anyString());
    }
    @ParameterizedTest
    @ValueSource(strings={"type","size","sha","invalid"})
    void should_reject_inconsistent_remote_metadata(String field) {
        setup(now);
        if(field.equals("invalid")) { when(storage.head("private","secret")).thenThrow(new IllegalArgumentException("private/secret")); }
        else { when(storage.head("private","secret")).thenReturn(new AudioObjectMetadata("private","secret",field.equals("type")?"audio/flac":"audio/mpeg",field.equals("size")?11:10,field.equals("sha")?"b".repeat(64):meta.sha256())); }
        assertThrows(TrackAudioStorageInconsistentException.class,()->service.prepare(42L,actor,now));
    }
    @Test
    void should_sanitize_missing_object_and_translate_database_failures() {
        setup(now);when(storage.head("private","secret")).thenThrow(new AudioObjectNotFoundException("private","secret"));
        assertEquals("Track not found: 42",assertThrows(TrackNotFoundException.class,()->service.prepare(42L,actor,now)).getMessage());
        doThrow(new DataAccessResourceFailureException("db")).when(query).load(42L,actor);
        assertThrows(TrackStoreUnavailableException.class,()->service.prepare(42L,actor,now));
        doThrow(new CannotCreateTransactionException("db")).when(query).load(42L,actor);
        assertThrows(TrackStoreUnavailableException.class,()->service.prepare(42L,actor,now));
    }
}
