package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.favorite.service.UnfavoriteTrackService;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.service.TrackArchiveService;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class UnfavoriteTrackIntegrationTest extends FavoriteIntegrationSupport {
    private static final String PATH = "/api/v1/users/me/favorites/";
    @Autowired TrackArchiveService archive;
    @Autowired JwtEncoder encoder;
    @MockitoSpyBean FavoriteRepository favorites;
    @MockitoSpyBean UnfavoriteTrackService service;
    @MockitoSpyBean PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetSpies() {
        reset(favorites, service, transactionManager);
    }

    void addFavorite(int owner, long trackId) {
        transaction().executeWithoutResult(status -> favorites.addIfAbsent(owner, trackId, LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    @ParameterizedTest
    @CsvSource({"USER,DRAFT", "USER,PUBLISHED", "USER,ARCHIVED", "ADMIN,DRAFT", "ADMIN,PUBLISHED", "ADMIN,ARCHIVED"})
    void should_delete_only_own_favorite_for_every_role_and_status(UserRole role, TrackStatus status) throws Exception {
        int owner = role == UserRole.USER ? userId : adminId;
        int other = owner == userId ? adminId : userId;
        long target = switch (status) {
            case DRAFT -> draftId;
            case PUBLISHED -> publishedId;
            case ARCHIVED -> archivedId;
        };
        long otherTrack = target == draftId ? publishedId : draftId;
        addFavorite(owner, target);
        addFavorite(other, target);
        addFavorite(owner, otherTrack);
        var otherUserBefore = jdbc.queryForMap("select * from user_favorites where user_id=? and track_id=?", other, target);
        var otherTrackBefore = jdbc.queryForMap("select * from user_favorites where user_id=? and track_id=?", owner, otherTrack);
        mvc.perform(delete(PATH + target).header("Authorization", bearer(owner, role)))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_favorites where user_id=? and track_id=?", Integer.class, owner, target));
        assertEquals(otherUserBefore, jdbc.queryForMap("select * from user_favorites where user_id=? and track_id=?", other, target));
        assertEquals(otherTrackBefore, jdbc.queryForMap("select * from user_favorites where user_id=? and track_id=?", owner, otherTrack));
    }

    @Test
    void should_allow_admin_logged_in_as_user_to_cancel_hidden_favorites() throws Exception {
        for (long id : List.of(draftId, archivedId)) {
            mvc.perform(put(PATH + id).header("Authorization", bearer(adminId, UserRole.ADMIN))).andExpect(status().isNoContent());
            mvc.perform(delete(PATH + id).header("Authorization", bearer(adminId, UserRole.USER))).andExpect(status().isNoContent());
            assertEquals(0, count(id));
        }
    }

    @Test
    void should_allow_user_to_cancel_after_archiving_removes_visibility() throws Exception {
        String token = bearer(userId, UserRole.USER);
        mvc.perform(put(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        archive.archive(publishedId, adminId);
        mvc.perform(delete(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        assertEquals(0, count(publishedId));
    }

    @Test
    void should_succeed_for_missing_favorite_repeated_cancel_and_deleted_track() throws Exception {
        String token = bearer(userId, UserRole.USER);
        addFavorite(userId, publishedId);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        }
        addFavorite(userId, archivedId);
        jdbc.update("delete from tracks where id=?", archivedId);
        mvc.perform(delete(PATH + archivedId).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(delete(PATH + Long.MAX_VALUE).header("Authorization", token)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_favorites", Integer.class));
    }

    @Test
    void should_ignore_forged_identity_and_keep_other_users_record() throws Exception {
        addFavorite(userId, publishedId);
        addFavorite(adminId, publishedId);
        var original = jdbc.queryForMap("select * from user_favorites where user_id=?", adminId);
        String token = bearer(userId, UserRole.USER);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(PATH + publishedId).header("Authorization", token)
                            .param("userId", String.valueOf(adminId)).param("role", "ADMIN").header("X-User-Id", adminId)
                            .contentType("application/json").content("{\"userId\":" + adminId + ",\"role\":\"ADMIN\"}"))
                    .andExpect(status().isNoContent());
            assertEquals(original, jdbc.queryForMap("select * from user_favorites where user_id=?", adminId));
        }
        assertEquals(1, count(publishedId));
    }

    @Test
    void should_create_new_favorite_time_after_unfavorite_and_refavorite() throws Exception {
        addFavorite(userId, publishedId);
        String token = bearer(userId, UserRole.USER);
        mvc.perform(delete(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        assertEquals(0, count(publishedId));
        LocalDateTime before = LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        mvc.perform(put(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        LocalDateTime after = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        var record = jdbc.queryForMap("select * from user_favorites where user_id=?", userId);
        var created = (LocalDateTime) record.get("created_at");
        assertFalse(created.isBefore(before));
        assertFalse(created.isAfter(after));
        assertNotEquals(LocalDateTime.of(2026, 1, 1, 0, 0), created);
        assertEquals(created, record.get("updated_at"));
        assertEquals(userId, record.get("created_by"));
        assertEquals(userId, record.get("updated_by"));
    }

    @Test
    void should_reject_missing_bad_and_revoked_authentication_without_business_calls() throws Exception {
        mvc.perform(delete(PATH + publishedId)).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH + publishedId).header("Authorization", "Bearer invalid.jwt.value")).andExpect(status().isUnauthorized());
        String token = bearer(userId, UserRole.USER);
        jdbc.update("update users set token_version=token_version+1 where id=?", userId);
        mvc.perform(delete(PATH + publishedId).header("Authorization", token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        String admin = bearer(adminId, UserRole.ADMIN);
        jdbc.update("update users set user_role='USER' where id=?", adminId);
        mvc.perform(delete(PATH + publishedId).header("Authorization", admin)).andExpect(status().isUnauthorized());
        String deleted = bearer(userId, UserRole.USER);
        jdbc.update("delete from users where id=?", userId);
        mvc.perform(delete(PATH + publishedId).header("Authorization", deleted)).andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites, service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "WRONG_SIGNATURE", "MISSING_ROLE", "MISSING_VERSION", "INVALID_SUBJECT"})
    void should_reject_invalid_signed_tokens(String kind) throws Exception {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("music-api").audience(List.of("music-client"))
                .subject(kind.equals("INVALID_SUBJECT") ? "0" : String.valueOf(userId)).issuedAt(now.minusSeconds(300))
                .expiresAt(kind.equals("EXPIRED") ? now.minusSeconds(120) : now.plusSeconds(300));
        if (!kind.equals("MISSING_ROLE")) {
            claims.claim("role", "USER");
        }
        if (!kind.equals("MISSING_VERSION")) {
            claims.claim("tokenVersion", 0L);
        }
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        if (kind.equals("WRONG_SIGNATURE")) {
            int start = token.lastIndexOf('.') + 1;
            token = token.substring(0, start) + (token.charAt(start) == 'A' ? 'B' : 'A') + token.substring(start + 1);
        }
        mvc.perform(delete(PATH + publishedId).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites, service);
    }

    @Test
    void should_restore_original_favorite_when_delete_rolls_back() throws Exception {
        addFavorite(userId, publishedId);
        var original = jdbc.queryForMap("select * from user_favorites where user_id=?", userId);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("private SQL after delete");
        }).when(favorites).deleteByUserAndTrack(userId, publishedId);
        try {
            mvc.perform(delete(PATH + publishedId).header("Authorization", bearer(userId, UserRole.USER)))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"));
        } finally {
            reset(favorites);
        }
        assertEquals(original, jdbc.queryForMap("select * from user_favorites where user_id=?", userId));
        mvc.perform(delete(PATH + publishedId).header("Authorization", bearer(userId, UserRole.USER))).andExpect(status().isNoContent());
        assertEquals(0, count(publishedId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXISTING", "NO_FAVORITE", "NO_TRACK"})
    void should_retry_idempotently_after_commit_acknowledgement_failure(String scenario) throws Exception {
        if (scenario.equals("EXISTING")) {
            addFavorite(userId, publishedId);
        }
        long target = scenario.equals("NO_TRACK") ? Long.MAX_VALUE : publishedId;
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransactionSystemException("commit acknowledgement unavailable");
        }).when(transactionManager).commit(any(TransactionStatus.class));
        String token = bearer(userId, UserRole.USER);
        try {
            mvc.perform(delete(PATH + target).header("Authorization", token)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"));
        } finally {
            reset(transactionManager);
        }
        assertEquals(0, count(target));
        mvc.perform(delete(PATH + target).header("Authorization", token)).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/42/"})
    void should_document_actual_delete_routing_for_missing_id_and_trailing_slash(String suffix) throws Exception {
        String path = "/api/v1/users/me/favorites" + suffix;
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        var result = mvc.perform(delete(path).header("Authorization", bearer(userId, UserRole.USER)))
                .andExpect(status().isInternalServerError()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertInstanceOf(org.springframework.web.servlet.resource.ResourceHttpRequestHandler.class, result.getHandler());
        verifyNoInteractions(favorites, service);
        assertEquals(0, jdbc.queryForObject("select count(*) from user_favorites", Integer.class));
    }

    @Test
    void should_document_both_put_and_delete_with_empty_204() throws Exception {
        String document = mvc.perform(get("/v3/api-docs").header("Authorization", bearer(userId, UserRole.USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var resource = new ObjectMapper().readTree(document).path("paths").path("/api/v1/users/me/favorites/{trackId}");
        var operation = resource.path("delete");
        assertFalse(operation.isMissingNode());
        assertFalse(operation.has("requestBody"));
        assertFalse(operation.path("responses").path("204").has("content"));
        for (String code : List.of("204", "400", "401", "500", "503")) {
            assertTrue(operation.path("responses").has(code), code);
        }
        var schema = operation.path("parameters").get(0).path("schema");
        assertEquals("int64", schema.path("format").asText());
        assertEquals(1, schema.path("minimum").asInt());
        assertEquals("9223372036854775807", schema.path("maximum").decimalValue().toBigIntegerExact().toString());
        assertTrue(resource.path("put").path("responses").has("404"));
        assertFalse(resource.path("put").path("responses").path("204").has("content"));
    }
}
