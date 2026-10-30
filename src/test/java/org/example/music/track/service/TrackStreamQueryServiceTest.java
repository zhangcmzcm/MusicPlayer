package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.Optional;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackNotFoundException;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class TrackStreamQueryServiceTest {
    private final TrackRepository tracks=mock(TrackRepository.class);
    private final MediaAssetRepository assets=mock(MediaAssetRepository.class);
    private final TrackStreamQueryService query=new TrackStreamQueryService(tracks,assets);
    private final AuthenticatedUserPrincipal user=new AuthenticatedUserPrincipal(1,UserRole.USER);
    private final AuthenticatedUserPrincipal admin=new AuthenticatedUserPrincipal(2,UserRole.ADMIN);
    private MediaAssetEntity setup(TrackStatus state) {
        var t=TrackEntity.createDraft("t","a",null,null,null,null,3L,null,"{}",LocalDateTime.now(),1);
        ReflectionTestUtils.setField(t,"status",state);when(tracks.findById(42L)).thenReturn(Optional.of(t));
        var a=MediaAssetEntity.create(MediaAssetKind.AUDIO,"private","secret","audio/mpeg","a.mp3",10L,"A".repeat(64),LocalDateTime.now(),1);
        when(assets.findById(3L)).thenReturn(Optional.of(a));return a;
    }
    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_check_visibility_before_audio_lookup(TrackStatus state) {
        setup(state);
        if(state!=TrackStatus.PUBLISHED) { assertThrows(TrackNotFoundException.class,()->query.load(42L,user));verifyNoInteractions(assets); }
        else { assertEquals("a".repeat(64),query.load(42L,user).metadata().sha256()); }
        assertEquals(42L,query.load(42L,admin).trackId());
    }
    @Test
    void should_not_find_missing_song_or_missing_asset() {
        assertThrows(TrackNotFoundException.class,()->query.load(42L,admin));verifyNoInteractions(assets);
        setup(TrackStatus.PUBLISHED);when(assets.findById(3L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,()->query.load(42L,user));
    }
    @ParameterizedTest
    @ValueSource(strings={"kind","contentType","sizeBytes","sha256","bucket","objectKey","createdAt"})
    void should_reject_bad_local_metadata(String field) {
        var a=setup(TrackStatus.PUBLISHED);
        Object bad=switch(field) { case "kind"->MediaAssetKind.COVER;case "sizeBytes"->0L;case "createdAt"->null;default->""; };
        ReflectionTestUtils.setField(a,field,bad);
        assertThrows(IllegalStateException.class,()->query.load(42L,user));
    }
}
