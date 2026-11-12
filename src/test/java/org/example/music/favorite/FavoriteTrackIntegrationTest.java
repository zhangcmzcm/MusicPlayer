package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.favorite.service.FavoriteTrackService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.service.TrackArchiveService;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class FavoriteTrackIntegrationTest extends FavoriteIntegrationSupport {
    private static final String PATH = "/api/v1/users/me/favorites/";
    @Autowired TrackArchiveService archive;
    @Autowired JwtEncoder encoder;
    @MockitoSpyBean FavoriteRepository favorites;
    @MockitoSpyBean FavoriteTrackService service;
    @MockitoSpyBean PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetSpies() {
        reset(favorites, service, transactionManager);
    }

    @Test
    void should_enforce_real_login_roles_and_preserve_first_audit_fields() throws Exception {
        String user = bearer(userId, UserRole.USER);
        String admin = bearer(adminId, UserRole.ADMIN);
        String downgraded = bearer(adminId, UserRole.USER);
        mvc.perform(put(PATH + publishedId).header("Authorization", user))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        var original = jdbc.queryForMap("select * from user_favorites where user_id=?", userId);
        mvc.perform(put(PATH + publishedId).header("Authorization", user)).andExpect(status().isNoContent());
        assertEquals(original, jdbc.queryForMap("select * from user_favorites where user_id=?", userId));
        for (long id : List.of(publishedId, draftId, archivedId)) {
            mvc.perform(put(PATH + id).header("Authorization", admin)).andExpect(status().isNoContent());
        }
        mvc.perform(put(PATH + publishedId).header("Authorization", downgraded)).andExpect(status().isNoContent());
        for (long id : List.of(draftId, archivedId)) {
            mvc.perform(put(PATH + id).header("Authorization", user)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
            mvc.perform(put(PATH + id).header("Authorization", downgraded)).andExpect(status().isNotFound());
        }
        assertEquals(2, count(publishedId));
        assertEquals(1, count(draftId));
        assertEquals(1, count(archivedId));
    }

    @Test
    void should_recheck_archived_track_for_existing_favorite() throws Exception {
        String user = bearer(userId, UserRole.USER);
        mvc.perform(put(PATH + publishedId).header("Authorization", user)).andExpect(status().isNoContent());
        archive.archive(publishedId, adminId);
        mvc.perform(put(PATH + publishedId).header("Authorization", user)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
        assertEquals(1, count(publishedId));
        mvc.perform(put(PATH + publishedId).header("Authorization", bearer(adminId, UserRole.ADMIN)))
                .andExpect(status().isNoContent());
        assertEquals(2, count(publishedId));
    }

    @Test
    void should_ignore_forged_identity_and_return_not_found_for_missing_track() throws Exception {
        mvc.perform(put(PATH + publishedId).header("Authorization", bearer(userId, UserRole.USER))
                        .param("userId", String.valueOf(adminId)).param("role", "ADMIN")
                        .header("X-User-Id", adminId).contentType("application/json")
                        .content("{\"userId\":" + adminId + ",\"role\":\"ADMIN\"}"))
                .andExpect(status().isNoContent());
        assertEquals(userId, jdbc.queryForObject("select user_id from user_favorites where track_id=?", Integer.class, publishedId));
        mvc.perform(put(PATH + Long.MAX_VALUE).header("Authorization", bearer(userId, UserRole.USER)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
    }

    @Test
    void should_reject_absent_bad_and_revoked_tokens_before_business() throws Exception {
        mvc.perform(put(PATH + publishedId)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH + publishedId).header("Authorization", "Bearer invalid.jwt.value"))
                .andExpect(status().isUnauthorized());
        String token = bearer(userId, UserRole.USER);
        jdbc.update("update users set token_version=token_version+1 where id=?", userId);
        mvc.perform(put(PATH + publishedId).header("Authorization", token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        String admin = bearer(adminId, UserRole.ADMIN);
        jdbc.update("update users set user_role='USER' where id=?", adminId);
        mvc.perform(put(PATH + publishedId).header("Authorization", admin)).andExpect(status().isUnauthorized());
        String deleted = bearer(userId, UserRole.USER);
        jdbc.update("delete from users where id=?", userId);
        mvc.perform(put(PATH + publishedId).header("Authorization", deleted)).andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "WRONG_SIGNATURE", "MISSING_ROLE", "MISSING_VERSION", "INVALID_SUBJECT"})
    void should_reject_expired_or_invalid_signed_claims(String kind) throws Exception {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("music-api").audience(List.of("music-client"))
                .subject(kind.equals("INVALID_SUBJECT") ? "0" : String.valueOf(userId))
                .issuedAt(now.minusSeconds(300))
                .expiresAt(kind.equals("EXPIRED") ? now.minusSeconds(120) : now.plusSeconds(300));
        if (!kind.equals("MISSING_ROLE")) {
            claims.claim("role", "USER");
        }
        if (!kind.equals("MISSING_VERSION")) {
            claims.claim("tokenVersion", 0L);
        }
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        if (kind.equals("WRONG_SIGNATURE")) {
            int signature = token.lastIndexOf('.') + 1;
            char altered = token.charAt(signature) == 'A' ? 'B' : 'A';
            token = token.substring(0, signature) + altered + token.substring(signature + 1);
        }
        mvc.perform(put(PATH + publishedId).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites);
    }

    @Test
    void should_reject_unauthenticated_service_calls_through_method_security() {
        SecurityContextHolder.clearContext();
        try {
            assertThrows(AuthenticationException.class,
                    () -> service.favorite(publishedId, new AuthenticatedUserPrincipal(userId, UserRole.USER)));
            verifyNoInteractions(favorites);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void should_roll_back_written_favorite_on_failure_and_allow_retry() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("private SQL after write");
        }).when(favorites).addIfAbsent(anyInt(), anyLong(), any());
        String token = bearer(userId, UserRole.USER);
        mvc.perform(put(PATH + publishedId).header("Authorization", token)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("歌曲收藏暂时无法完成"));
        assertEquals(0, count(publishedId));
        reset(favorites);
        mvc.perform(put(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        assertEquals(1, count(publishedId));
    }

    @Test
    void should_keep_first_time_when_commit_succeeds_but_client_receives_failure() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransactionSystemException("commit acknowledgement unavailable");
        }).when(transactionManager).commit(any(TransactionStatus.class));
        String token = bearer(userId, UserRole.USER);
        try {
            mvc.perform(put(PATH + publishedId).header("Authorization", token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"));
        } finally {
            reset(transactionManager);
        }
        assertEquals(1, count(publishedId));
        var first = jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId);
        mvc.perform(put(PATH + publishedId).header("Authorization", token)).andExpect(status().isNoContent());
        assertEquals(first, jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/42/"})
    void should_document_actual_routing_for_missing_id_and_trailing_slash(String suffix) throws Exception {
        String path = "/api/v1/users/me/favorites" + suffix;
        mvc.perform(put(path)).andExpect(status().isUnauthorized());
        // The existing global catch-all translates unmatched static-resource requests to INTERNAL_ERROR.
        var result = mvc.perform(put(path).header("Authorization", bearer(userId, UserRole.USER)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertInstanceOf(org.springframework.web.servlet.resource.ResourceHttpRequestHandler.class, result.getHandler());
        verifyNoInteractions(favorites, service);
        assertEquals(0, jdbc.queryForObject("select count(*) from user_favorites", Long.class));
    }

    @Test
    void should_publish_openapi_for_single_put_with_empty_204() throws Exception {
        var response = mvc.perform(get("/v3/api-docs").header("Authorization", bearer(userId, UserRole.USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var operation = new ObjectMapper().readTree(response).path("paths")
                .path("/api/v1/users/me/favorites/{trackId}").path("put");
        assertFalse(operation.isMissingNode());
        assertFalse(operation.path("responses").path("204").has("content"));
        assertFalse(operation.has("requestBody"));
        for (String code : List.of("204", "400", "401", "404", "500", "503")) {
            assertTrue(operation.path("responses").has(code), code);
        }
    }
}
