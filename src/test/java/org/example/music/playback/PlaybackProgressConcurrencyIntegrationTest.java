package org.example.music.playback;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.repository.PlaybackProgressRepository;
import org.example.music.playback.service.PlaybackProgressTransactionService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.service.TrackArchiveService;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Timeout(60)
class PlaybackProgressConcurrencyIntegrationTest extends PlaybackProgressIntegrationSupport {
    @Autowired PlaybackProgressTransactionService transactionService;
    @Autowired TrackArchiveService archive;
    @MockitoSpyBean PlaybackProgressRepository repository;

    private void awaitLockWait(String table) {
        var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(10)).until(() -> observer.queryForObject("""
                select count(*) from performance_schema.data_lock_waits w
                join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id
                    and l.engine=w.engine
                where l.object_schema=database() and l.object_name=?
                """, Long.class, table) > 0);
    }

    private int behindTrackLock(long id, Callable<Integer> competing, Runnable holderAction) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = new ArrayList<Future<Integer>>();
            try {
                transaction().executeWithoutResult(status -> {
                    jdbc.queryForList("select id from tracks where id=? for update", id);
                    pending.add(executor.submit(competing));
                    awaitLockWait("tracks");
                    holderAction.run();
                });
                return pending.getFirst().get(15, TimeUnit.SECONDS);
            } finally {
                pending.forEach(future -> future.cancel(true));
            }
        }
    }

    @Test
    void should_keep_one_row_and_timestamp_for_concurrent_identical_first_events() throws Exception {
        String user = bearer(userId, UserRole.USER);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var pending = new ArrayList<Future<String>>();
            try {
                for (int index = 0; index < 12; index++) {
                    pending.add(executor.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return save(DEVICE, publishedId, 83, 42, user).andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString();
                    }));
                }
                start.countDown();
                var times = new java.util.HashSet<String>();
                for (var result : pending) {
                    times.add(new ObjectMapper().readTree(result.get(20, TimeUnit.SECONDS)).path("updatedAt").asText());
                }
                assertEquals(1, times.size());
            } finally {
                start.countDown();
                pending.forEach(future -> future.cancel(true));
            }
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
        var first = progress(userId, DEVICE);
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isOk());
        assertEquals(first, progress(userId, DEVICE));
    }

    @Test
    void should_reject_conflicting_first_event_with_same_sequence() throws Exception {
        String user = bearer(userId, UserRole.USER);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return save(DEVICE, publishedId, 10, 42, user).andReturn().getResponse().getStatus();
            };
            Callable<Integer> second = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return save(DEVICE, publishedId, 20, 42, user).andReturn().getResponse().getStatus();
            };
            var a = executor.submit(first);
            var b = executor.submit(second);
            start.countDown();
            int firstStatus = a.get(20, TimeUnit.SECONDS);
            int secondStatus = b.get(20, TimeUnit.SECONDS);
            assertEquals(Set.of(200, 409), Set.copyOf(List.of(firstStatus, secondStatus)));
            long winnerPosition = firstStatus == 200 ? 10 : 20;
            assertEquals(winnerPosition, progress(userId, DEVICE).get("position_seconds"));
            save(DEVICE, publishedId, winnerPosition == 10 ? 20 : 10, 42, user).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_SEQUENCE_CONFLICT"))
                    .andExpect(jsonPath("$.currentSequence").value(42));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void should_keep_largest_sequence_for_both_lock_acquisition_orders(boolean largerFirst) throws Exception {
        String user = bearer(userId, UserRole.USER);
        var actor = new AuthenticatedUserPrincipal(userId, UserRole.USER);
        long firstSequence = largerFirst ? 43 : 42;
        long secondSequence = largerFirst ? 42 : 43;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = new ArrayList<Future<Integer>>();
            transaction().executeWithoutResult(status -> {
                transactionService.save(DEVICE, new SavePlaybackProgressRequest(publishedId,
                        largerFirst ? 30L : 100L, firstSequence), actor);
                pending.add(executor.submit(() -> save(DEVICE, publishedId, largerFirst ? 100 : 30,
                        secondSequence, user).andReturn().getResponse().getStatus()));
                awaitLockWait("user_playback_progress");
            });
            assertEquals(largerFirst ? 409 : 200, pending.getFirst().get(15, TimeUnit.SECONDS));
        }
        assertEquals(43L, progress(userId, DEVICE).get("last_sequence"));
        assertEquals(30L, progress(userId, DEVICE).get("position_seconds"));
    }

    @Test
    void should_allow_independent_devices_to_share_track_read_lock() throws Exception {
        String user = bearer(userId, UserRole.USER);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                transactionService.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 83L, 42L),
                        new AuthenticatedUserPrincipal(userId, UserRole.USER));
                try {
                    assertEquals(200, executor.submit(() -> save(OTHER_DEVICE, publishedId, 30, 1, user)
                            .andReturn().getResponse().getStatus()).get(10, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        }
        assertEquals(2, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
    }

    @Test
    void should_return_not_found_when_archive_commits_before_save() throws Exception {
        String user = bearer(userId, UserRole.USER);
        assertEquals(404, behindTrackLock(publishedId,
                () -> save(DEVICE, publishedId, 83, 42, user).andReturn().getResponse().getStatus(),
                () -> archive.archive(publishedId, adminId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
    }

    @Test
    void should_preserve_progress_when_archive_waits_for_save_commit() throws Exception {
        assertEquals(1, behindTrackLock(publishedId, () -> {
            archive.archive(publishedId, adminId);
            return 1;
        }, () -> transactionService.save(DEVICE, new SavePlaybackProgressRequest(publishedId, 83L, 42L),
                new AuthenticatedUserPrincipal(userId, UserRole.USER))));
        assertEquals(42L, progress(userId, DEVICE).get("last_sequence"));
        assertEquals("ARCHIVED", jdbc.queryForObject("select status from tracks where id=?", String.class, publishedId));
    }

    @Test
    void should_return_not_found_when_delete_commits_before_save() throws Exception {
        String admin = bearer(adminId, UserRole.ADMIN);
        assertEquals(404, behindTrackLock(archivedId,
                () -> save(DEVICE, archivedId, 83, 42, admin).andReturn().getResponse().getStatus(),
                () -> jdbc.update("delete from tracks where id=?", archivedId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
    }

    @Test
    void should_preserve_sequence_when_delete_waits_for_save_commit() throws Exception {
        assertEquals(1, behindTrackLock(archivedId,
                () -> jdbc.update("delete from tracks where id=?", archivedId),
                () -> transactionService.save(DEVICE, new SavePlaybackProgressRequest(archivedId, 83L, 42L),
                        new AuthenticatedUserPrincipal(adminId, UserRole.ADMIN))));
        var cleared = progress(adminId, DEVICE);
        assertNull(cleared.get("track_id"));
        assertEquals(42L, cleared.get("last_sequence"));
        assertEquals(83L, cleared.get("position_seconds"));
        String admin = bearer(adminId, UserRole.ADMIN);
        save(DEVICE, archivedId, 83, 42, admin).andExpect(status().isNotFound());
        save(DEVICE, publishedId, 0, 41, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_STALE_SEQUENCE"));
        assertEquals(cleared, progress(adminId, DEVICE));
        save(DEVICE, publishedId, 0, 43, admin).andExpect(status().isOk());
    }

    @Test
    void should_rollback_when_account_disappears_after_authentication() throws Exception {
        String user = bearer(userId, UserRole.USER);
        assertEquals(503, behindTrackLock(publishedId,
                () -> save(DEVICE, publishedId, 83, 42, user).andReturn().getResponse().getStatus(),
                () -> jdbc.update("delete from users where id=?", userId)));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isUnauthorized());
    }

    @Test
    void should_return_503_for_real_lock_timeout_then_allow_retry() throws Exception {
        String user = bearer(userId, UserRole.USER);
        doAnswer(invocation -> {
            int previous = jdbc.queryForObject("select @@session.innodb_lock_wait_timeout", Integer.class);
            jdbc.execute("set session innodb_lock_wait_timeout=1");
            try {
                return invocation.callRealMethod();
            } finally {
                jdbc.execute("set session innodb_lock_wait_timeout=" + previous);
            }
        }).when(repository).lockTrackForShare(anyLong());
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                jdbc.queryForList("select id from tracks where id=? for update", publishedId);
                var pending = executor.submit(() -> save(DEVICE, publishedId, 83, 42, user)
                        .andReturn().getResponse().getStatus());
                try {
                    assertEquals(503, pending.get(10, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                } finally {
                    pending.cancel(true);
                }
            });
        } finally {
            reset(repository);
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from user_playback_progress", Integer.class));
        save(DEVICE, publishedId, 83, 42, user).andExpect(status().isOk());
    }

    @Test
    void should_return_503_for_real_deadlock_and_retry_original_sequence() throws Exception {
        String user = bearer(userId, UserRole.USER);
        long previousTrack = createTrack("PUBLISHED", 215430L);
        transactionService.save(DEVICE, new SavePlaybackProgressRequest(previousTrack, 20L, 10L),
                new AuthenticatedUserPrincipal(userId, UserRole.USER));
        var original = progress(userId, DEVICE);
        var weightTracks = new ArrayList<Long>();
        for (int index = 0; index < 20; index++) {
            weightTracks.add(createTrack("PUBLISHED", 215430L));
        }
        var databaseFailure = new AtomicReference<Throwable>();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (RuntimeException failure) {
                databaseFailure.set(failure);
                throw failure;
            }
        }).when(repository).insertIfAbsent(anyInt(), anyString(), anyLong(), anyLong(), anyLong(), any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = new ArrayList<Future<Integer>>();
            transaction().executeWithoutResult(status -> {
                jdbc.queryForList("select * from user_playback_progress where user_id=? and device_id=? for update", userId, DEVICE);
                // A heavier test holder makes the saving transaction the deterministic deadlock victim.
                for (long id : weightTracks) {
                    jdbc.update("update tracks set title=concat(title,'x') where id=?", id);
                }
                long holderId = jdbc.queryForObject("select connection_id()", Long.class);
                pending.add(executor.submit(() -> save(DEVICE, publishedId, 83, 11, user)
                        .andExpect(jsonPath("$.code").value("PLAYBACK_PROGRESS_STORE_UNAVAILABLE"))
                        .andReturn().getResponse().getStatus()));
                awaitLockWait("user_playback_progress");
                var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
                await().atMost(Duration.ofSeconds(10)).until(() -> {
                    var holder = observer.queryForList("select trx_weight from information_schema.innodb_trx where trx_mysql_thread_id=?", Long.class, holderId);
                    var waiting = observer.queryForList("select trx_weight from information_schema.innodb_trx where trx_mysql_thread_id<>? and trx_state='LOCK WAIT'", Long.class, holderId);
                    return holder.size() == 1 && waiting.size() == 1 && holder.getFirst() > waiting.getFirst();
                });
                jdbc.update("update tracks set title=concat(title,'x') where id=?", publishedId);
            });
            assertEquals(503, pending.getFirst().get(15, TimeUnit.SECONDS));
        } finally {
            reset(repository);
        }
        boolean deadlock = false;
        for (Throwable cause = databaseFailure.get(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) {
                deadlock = true;
            }
        }
        assertTrue(deadlock, "Expected a real MySQL deadlock with error 1213 / SQLSTATE 40001");
        assertEquals(original, progress(userId, DEVICE));
        save(DEVICE, publishedId, 83, 11, user).andExpect(status().isOk());
        var retried = progress(userId, DEVICE);
        save(DEVICE, publishedId, 83, 11, user).andExpect(status().isOk());
        assertEquals(retried, progress(userId, DEVICE));
    }
}
