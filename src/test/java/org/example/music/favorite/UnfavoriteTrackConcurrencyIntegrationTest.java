package org.example.music.favorite;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.favorite.service.FavoriteTrackTransactionService;
import org.example.music.favorite.service.UnfavoriteTrackTransactionService;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.service.TrackArchiveService;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Timeout(45)
class UnfavoriteTrackConcurrencyIntegrationTest extends FavoriteIntegrationSupport {
    @MockitoSpyBean FavoriteRepository favorites;
    @Autowired FavoriteTrackTransactionService favoriteTransaction;
    @Autowired UnfavoriteTrackTransactionService unfavoriteTransaction;
    @Autowired TrackArchiveService archive;

    void addFavorite(int owner, long trackId) {
        transaction().executeWithoutResult(status -> favorites.addIfAbsent(owner, trackId, LocalDateTime.of(2026, 1, 1, 0, 0)));
    }

    int cancel(long id, String token) throws Exception {
        return mvc.perform(delete("/api/v1/users/me/favorites/" + id).header("Authorization", token))
                .andReturn().getResponse().getStatus();
    }

    void awaitDatabaseLockWait() {
        var observer = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(10)).until(() ->
                observer.queryForObject("select count(*) from performance_schema.data_lock_waits", Long.class) > 0);
    }

    int runBehindTrackLock(long id, Callable<Integer> competing, Runnable holderAction) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            List<Future<Integer>> pending = new ArrayList<>();
            try {
                transaction().executeWithoutResult(status -> {
                    favorites.lockTrackStatus(id).orElseThrow();
                    pending.add(executor.submit(competing));
                    awaitDatabaseLockWait();
                    holderAction.run();
                });
                return pending.getFirst().get(15, TimeUnit.SECONDS);
            } finally {
                pending.forEach(future -> future.cancel(true));
            }
        }
    }

    @Test
    void should_succeed_for_twelve_concurrent_cancellations() throws Exception {
        addFavorite(userId, publishedId);
        String token = bearer(userId, UserRole.USER);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var requests = new ArrayList<Callable<Integer>>();
            for (int index = 0; index < 12; index++) {
                requests.add(() -> cancel(publishedId, token));
            }
            for (var result : executor.invokeAll(requests, 20, TimeUnit.SECONDS)) {
                assertEquals(204, result.get());
            }
        }
        assertEquals(0, count(publishedId));
    }

    @Test
    void should_isolate_users_and_other_tracks_under_concurrent_cancellation() throws Exception {
        int uninvolved = createUser("favorite-uninvolved", "USER");
        addFavorite(userId, publishedId);
        addFavorite(adminId, publishedId);
        addFavorite(uninvolved, publishedId);
        addFavorite(userId, draftId);
        var otherUser = jdbc.queryForMap("select * from user_favorites where user_id=?", uninvolved);
        var otherTrack = jdbc.queryForMap("select * from user_favorites where track_id=?", draftId);
        String user = bearer(userId, UserRole.USER);
        String admin = bearer(adminId, UserRole.ADMIN);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cancel(publishedId, user));
            var second = executor.submit(() -> cancel(publishedId, admin));
            assertEquals(204, first.get(15, TimeUnit.SECONDS));
            assertEquals(204, second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, count(publishedId));
        assertEquals(otherUser, jdbc.queryForMap("select * from user_favorites where user_id=?", uninvolved));
        assertEquals(otherTrack, jdbc.queryForMap("select * from user_favorites where track_id=?", draftId));
    }

    @Test
    void should_remove_favorite_when_favorite_commits_before_cancel() throws Exception {
        String token = bearer(userId, UserRole.USER);
        assertEquals(204, runBehindTrackLock(publishedId, () -> cancel(publishedId, token),
                () -> favoriteTransaction.favorite(publishedId, new AuthenticatedUserPrincipal(userId, UserRole.USER))));
        assertEquals(0, count(publishedId));
    }

    @Test
    void should_recreate_favorite_when_cancel_commits_before_favorite() throws Exception {
        addFavorite(userId, publishedId);
        String token = bearer(userId, UserRole.USER);
        assertEquals(204, runBehindTrackLock(publishedId, () -> mvc.perform(
                        put("/api/v1/users/me/favorites/" + publishedId).header("Authorization", token))
                        .andReturn().getResponse().getStatus(),
                () -> unfavoriteTransaction.unfavorite(publishedId, new AuthenticatedUserPrincipal(userId, UserRole.USER))));
        assertEquals(1, count(publishedId));
        assertNotEquals(LocalDateTime.of(2026, 1, 1, 0, 0),
                jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId).get("created_at"));
    }

    @Test
    void should_cancel_after_archive_commits() throws Exception {
        addFavorite(userId, publishedId);
        String token = bearer(userId, UserRole.USER);
        assertEquals(204, runBehindTrackLock(publishedId, () -> cancel(publishedId, token),
                () -> archive.archive(publishedId, adminId)));
        assertEquals(0, count(publishedId));
        assertEquals("ARCHIVED", jdbc.queryForObject("select status from tracks where id=?", String.class, publishedId));
    }

    @Test
    void should_archive_after_cancel_commits() throws Exception {
        addFavorite(userId, publishedId);
        assertEquals(1, runBehindTrackLock(publishedId, () -> {
            archive.archive(publishedId, adminId);
            return 1;
        }, () -> unfavoriteTransaction.unfavorite(publishedId, new AuthenticatedUserPrincipal(userId, UserRole.USER))));
        assertEquals(0, count(publishedId));
        assertEquals("ARCHIVED", jdbc.queryForObject("select status from tracks where id=?", String.class, publishedId));
    }

    @Test
    void should_succeed_when_track_delete_commits_before_cancel() throws Exception {
        addFavorite(userId, archivedId);
        String token = bearer(userId, UserRole.USER);
        assertEquals(204, runBehindTrackLock(archivedId, () -> cancel(archivedId, token),
                () -> jdbc.update("delete from tracks where id=? and status='ARCHIVED'", archivedId)));
        assertEquals(0, count(archivedId));
    }

    @Test
    void should_keep_relation_absent_when_track_delete_waits_for_cancel() throws Exception {
        addFavorite(userId, archivedId);
        assertEquals(1, runBehindTrackLock(archivedId,
                () -> jdbc.update("delete from tracks where id=? and status='ARCHIVED'", archivedId),
                () -> unfavoriteTransaction.unfavorite(archivedId, new AuthenticatedUserPrincipal(userId, UserRole.USER))));
        assertEquals(0, count(archivedId));
    }

    @Test
    void should_complete_other_track_cancellation_while_one_track_is_locked() throws Exception {
        addFavorite(userId, draftId);
        String token = bearer(userId, UserRole.USER);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                favorites.lockTrackStatus(publishedId).orElseThrow();
                try {
                    assertEquals(204, executor.submit(() -> cancel(draftId, token)).get(10, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        assertEquals(0, count(draftId));
    }

    @Test
    void should_preserve_original_favorite_on_lock_timeout_then_allow_retry() throws Exception {
        addFavorite(userId, publishedId);
        var original = jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId);
        String token = bearer(userId, UserRole.USER);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                favorites.lockTrackStatus(publishedId).orElseThrow();
                doAnswer(invocation -> {
                    int originalTimeout = jdbc.queryForObject("select @@session.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("set session innodb_lock_wait_timeout=1");
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        jdbc.execute("set session innodb_lock_wait_timeout=" + originalTimeout);
                    }
                }).when(favorites).lockTrackStatus(publishedId);
                try {
                    assertEquals(503, executor.submit(() -> cancel(publishedId, token)).get(10, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                } finally {
                    reset(favorites);
                }
            });
        }
        assertEquals(original, jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId));
        assertEquals(204, cancel(publishedId, token));
        assertEquals(0, count(publishedId));
    }
}
