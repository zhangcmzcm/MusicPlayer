package org.example.music.playback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.example.music.playback.repository.PlaybackProgressRepository;
import org.example.music.user.domain.UserRole;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class PlaybackProgressIntegrationTest extends PlaybackProgressIntegrationSupport {
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean PlaybackProgressRepository repository;
    @MockitoSpyBean PlatformTransactionManager transactionManager;
    @MockitoSpyBean UserAuthorizationStateService authorizationState;

    private String key(int id) {
        return "music:playback-progress:user:" + id;
    }

    @Test
    void should_preserve_and_advance_state_through_real_jwt() throws Exception {
        String token = bearer(userId, UserRole.USER);
        save(DEVICE, publishedId, 83, 42, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(DEVICE)).andExpect(jsonPath("$.sequence").value(42))
                .andExpect(jsonPath("$.positionSeconds").value(83)).andExpect(header().string("Cache-Control", "no-store"));
        var first = progress(userId, DEVICE);
        save(DEVICE, publishedId, 83, 42, token).andExpect(status().isOk());
        assertEquals(first, progress(userId, DEVICE));
        save(DEVICE, publishedId, 30, 43, token).andExpect(status().isOk());
        save(DEVICE, publishedId, 0, 44, token).andExpect(status().isOk());
        save(DEVICE, publishedId, 83, 42, token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_STALE_SEQUENCE"))
                .andExpect(jsonPath("$.currentSequence").value(44));
        save(DEVICE, publishedId, 1, 44, token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_SEQUENCE_CONFLICT"));
        assertEquals(0L, progress(userId, DEVICE).get("position_seconds"));
        assertEquals("6", redis.opsForValue().get(key(userId)));
    }

    @Test
    void should_enforce_current_login_role_for_all_track_states() throws Exception {
        String user = bearer(userId, UserRole.USER);
        String admin = bearer(adminId, UserRole.ADMIN);
        String adminAsUser = bearer(adminId, UserRole.USER);
        long sequence = 1;
        for (long id : List.of(draftId, archivedId)) {
            save(DEVICE, id, 0, sequence, user).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND"));
            save(DEVICE, id, 0, sequence, adminAsUser).andExpect(status().isNotFound());
            save(DEVICE, id, 0, sequence++, admin).andExpect(status().isOk());
        }
        save(DEVICE, publishedId, 83, sequence++, adminAsUser).andExpect(status().isOk());
        save(DEVICE, Long.MAX_VALUE, 0, sequence, admin).andExpect(status().isNotFound());
        assertEquals("6", redis.opsForValue().get(key(adminId)));
    }

    @Test
    void should_isolate_users_and_devices_and_normalize_case() throws Exception {
        String user = bearer(userId, UserRole.USER);
        String admin = bearer(adminId, UserRole.ADMIN);
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isOk());
        save(DEVICE, draftId, 20, 1, admin).andExpect(status().isOk());
        save(OTHER_DEVICE, publishedId, 30, 1, user).andExpect(status().isOk());
        save(DEVICE.toUpperCase(Locale.ROOT), publishedId, 10, 43, user).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(DEVICE));
        assertEquals(3, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
        assertEquals(43L, progress(userId, DEVICE).get("last_sequence"));
        assertEquals(1L, progress(userId, OTHER_DEVICE).get("last_sequence"));
        assertEquals(draftId, progress(adminId, DEVICE).get("track_id"));
    }

    @Test
    void should_validate_duration_and_preserve_legacy_unknown_duration() throws Exception {
        String user = bearer(userId, UserRole.USER);
        save(DEVICE, publishedId, 215, 1, user).andExpect(status().isOk());
        save(DEVICE, publishedId, 216, 2, user).andExpect(status().isBadRequest());
        jdbc.update("update tracks set duration_ms=900 where id=?", publishedId);
        save(DEVICE, publishedId, 1, 2, user).andExpect(status().isBadRequest());
        save(DEVICE, publishedId, 0, 2, user).andExpect(status().isOk());
        jdbc.update("update tracks set duration_ms=null where id=?", publishedId);
        save(DEVICE, publishedId, Long.MAX_VALUE, 3, user).andExpect(status().isOk());
        jdbc.update("update tracks set duration_ms=0 where id=?", publishedId);
        save(DEVICE, publishedId, Long.MAX_VALUE, 4, user).andExpect(status().isOk());
        assertEquals(Long.MAX_VALUE, progress(userId, DEVICE).get("position_seconds"));
        assertEquals("6", redis.opsForValue().get(key(userId)));
    }

    @Test
    void should_share_account_quota_across_devices_and_login_roles() throws Exception {
        redis.opsForValue().set(key(adminId), "118", Duration.ofSeconds(60));
        save(DEVICE, publishedId, 83, 1, bearer(adminId, UserRole.USER)).andExpect(status().isOk());
        save(OTHER_DEVICE, draftId, 0, 1, bearer(adminId, UserRole.ADMIN)).andExpect(status().isOk());
        save(DEVICE, publishedId, 83, 2, bearer(adminId, UserRole.USER)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        assertEquals("120", redis.opsForValue().get(key(adminId)));
        assertEquals(1L, progress(adminId, DEVICE).get("last_sequence"));
        save(DEVICE, publishedId, 0, 1, bearer(userId, UserRole.USER)).andExpect(status().isOk());
    }

    @Test
    void should_reject_invalid_input_before_consuming_quota() throws Exception {
        String user = bearer(userId, UserRole.USER);
        mvc.perform(put(PATH + DEVICE).header("Authorization", user).contentType("application/json")
                        .content("{\"trackId\":1,\"positionSeconds\":1.5,\"sequence\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        save("invalid", publishedId, 0, 1, user).andExpect(status().isBadRequest());
        assertNull(redis.opsForValue().get(key(userId)));
        verifyNoInteractions(repository);
    }

    @Test
    void should_return_quota_unavailable_before_database_access() throws Exception {
        redis.opsForHash().put(key(userId), "invalid", "state");
        save(DEVICE, publishedId, 83, 42, bearer(userId, UserRole.USER)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_RATE_LIMIT_UNAVAILABLE"));
        verifyNoInteractions(repository);
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
    }

    @Test
    void should_rollback_partial_write_and_allow_original_event_retry() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("private SQL after write");
        }).when(repository).insertIfAbsent(anyInt(), anyString(), anyLong(), anyLong(), anyLong(), any());
        String user = bearer(userId, UserRole.USER);
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_STORE_UNAVAILABLE"));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
        reset(repository);
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isOk());
        assertEquals("2", redis.opsForValue().get(key(userId)));
    }

    @Test
    void should_preserve_saved_event_after_commit_ack_failure_and_retry_idempotently() throws Exception {
        String user = bearer(userId, UserRole.USER);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransactionSystemException("commit acknowledgement unavailable");
        }).when(transactionManager).commit(any(TransactionStatus.class));
        try {
            save(DEVICE, publishedId, 83, 42, user).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_STORE_UNAVAILABLE"));
        } finally {
            reset(transactionManager);
        }
        var first = progress(userId, DEVICE);
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isOk());
        assertEquals(first, progress(userId, DEVICE));
    }

    @Test
    void should_check_authentication_state_before_quota() throws Exception {
        String user = bearer(userId, UserRole.USER);
        doThrow(new AuthStateStoreUnavailableException("private", new RuntimeException("private")))
                .when(authorizationState).findSnapshot(userId);
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_STORE_UNAVAILABLE"));
        assertNull(redis.opsForValue().get(key(userId)));
        verifyNoInteractions(repository);
    }

    @Test
    void should_revoke_stale_version_or_role_tokens() throws Exception {
        String user = bearer(userId, UserRole.USER);
        jdbc.update("update users set token_version=token_version+1 where id=?", userId);
        save(DEVICE, publishedId, 0, 1, user).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        String admin = bearer(adminId, UserRole.ADMIN);
        jdbc.update("update users set user_role='USER',token_version=token_version+1 where id=?", adminId);
        save(DEVICE, publishedId, 0, 1, admin).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        assertNull(redis.opsForValue().get(key(userId)));
        assertNull(redis.opsForValue().get(key(adminId)));
    }

    @Test
    void should_publish_openapi_for_single_put_and_request_response_schemas() throws Exception {
        String response = mvc.perform(get("/v3/api-docs").header("Authorization", bearer(userId, UserRole.USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = new ObjectMapper().readTree(response);
        var path = document.path("paths").path("/api/v1/users/me/playback-progress/{deviceId}");
        assertEquals(1, path.size());
        var operation = path.path("put");
        assertTrue(operation.path("requestBody").path("required").asBoolean());
        for (String status : List.of("200", "400", "401", "403", "404", "409", "415", "429", "500", "503")) {
            assertTrue(operation.path("responses").has(status), status);
        }
        var schemas = document.path("components").path("schemas");
        assertEquals(3, schemas.path("SavePlaybackProgressRequest").path("properties").size());
        assertEquals(5, schemas.path("SavePlaybackProgressResponse").path("properties").size());
    }
}
