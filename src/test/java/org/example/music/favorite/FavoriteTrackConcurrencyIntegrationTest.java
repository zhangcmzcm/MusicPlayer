package org.example.music.favorite;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.favorite.service.FavoriteTrackTransactionService;
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
class FavoriteTrackConcurrencyIntegrationTest extends FavoriteIntegrationSupport {
    @MockitoSpyBean FavoriteRepository favorites;
    @Autowired FavoriteTrackTransactionService favoriteTransaction;
    @Autowired TrackArchiveService archive;

    int request(long id, String token) throws Exception {
        return mvc.perform(put("/api/v1/users/me/favorites/" + id).header("Authorization", token))
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
    void should_keep_one_first_favorite_under_concurrent_requests() throws Exception {
        String token = bearer(userId, UserRole.USER);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var requests = new ArrayList<Callable<Integer>>();
            for (int index = 0; index < 12; index++) {
                requests.add(() -> request(publishedId, token));
            }
            for (var result : executor.invokeAll(requests, 20, TimeUnit.SECONDS)) {
                assertEquals(204, result.get());
            }
        }
        assertEquals(1, count(publishedId));
        var first = jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId);
        assertEquals(204, request(publishedId, token));
        assertEquals(first, jdbc.queryForMap("select * from user_favorites where track_id=?", publishedId));
    }

    @Test
    void should_allow_two_users_to_favorite_the_same_track_concurrently() throws Exception {
        String user = bearer(userId, UserRole.USER);
        String admin = bearer(adminId, UserRole.ADMIN);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> request(publishedId, user));
            var second = executor.submit(() -> request(publishedId, admin));
            assertEquals(204, first.get(15, TimeUnit.SECONDS));
            assertEquals(204, second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2, count(publishedId));
    }

    @Test
    void should_return_not_found_when_archive_commits_before_user_favorite() throws Exception {
        String user = bearer(userId, UserRole.USER);
        assertEquals(404, runBehindTrackLock(publishedId, () -> request(publishedId, user),
                () -> archive.archive(publishedId, adminId)));
        assertEquals(0, count(publishedId));
        assertEquals(204, request(publishedId, bearer(adminId, UserRole.ADMIN)));
    }

    @Test
    void should_preserve_favorite_when_archive_waits_for_favorite_commit() throws Exception {
        assertEquals(1, runBehindTrackLock(publishedId, () -> {
            archive.archive(publishedId, adminId);
            return 1;
        }, () -> favoriteTransaction.favorite(publishedId, new AuthenticatedUserPrincipal(userId, UserRole.USER))));
        assertEquals(1, count(publishedId));
        assertEquals("ARCHIVED", jdbc.queryForObject("select status from tracks where id=?", String.class, publishedId));
    }

    @Test
    void should_return_not_found_when_delete_commits_before_favorite() throws Exception {
        String token = bearer(adminId, UserRole.ADMIN);
        assertEquals(404, runBehindTrackLock(archivedId, () -> request(archivedId, token),
                () -> jdbc.update("delete from tracks where id=? and status='ARCHIVED'", archivedId)));
        assertEquals(0, count(archivedId));
    }

    @Test
    void should_cascade_favorite_when_delete_waits_for_favorite_commit() throws Exception {
        assertEquals(1, runBehindTrackLock(archivedId,
                () -> jdbc.update("delete from tracks where id=? and status='ARCHIVED'", archivedId),
                () -> favoriteTransaction.favorite(archivedId, new AuthenticatedUserPrincipal(adminId, UserRole.ADMIN))));
        assertEquals(0, count(archivedId));
    }

    @Test
    void should_roll_back_when_user_disappears_after_authentication() throws Exception {
        String token = bearer(userId, UserRole.USER);
        assertEquals(503, runBehindTrackLock(publishedId, () -> request(publishedId, token),
                () -> jdbc.update("delete from users where id=?", userId)));
        assertEquals(0, count(publishedId));
        assertEquals(401, request(publishedId, token));
    }

    @Test
    void should_complete_other_track_favorite_while_one_track_is_locked() throws Exception {
        String token = bearer(adminId, UserRole.ADMIN);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                favorites.lockTrackStatus(publishedId).orElseThrow();
                try {
                    assertEquals(204, executor.submit(() -> request(draftId, token)).get(10, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
        assertEquals(1, count(draftId));
    }

    @Test
    void should_return_503_for_real_lock_timeout_then_allow_retry() throws Exception {
        String token = bearer(userId, UserRole.USER);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction().executeWithoutResult(status -> {
                favorites.lockTrackStatus(publishedId).orElseThrow();
                doAnswer(invocation -> {
                    int original = jdbc.queryForObject("select @@session.innodb_lock_wait_timeout", Integer.class);
                    jdbc.execute("set session innodb_lock_wait_timeout=1");
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        jdbc.execute("set session innodb_lock_wait_timeout=" + original);
                    }
                }).when(favorites).lockTrackStatus(publishedId);
                try {
                    assertEquals(503, executor.submit(() -> request(publishedId, token)).get(10, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                } finally {
                    reset(favorites);
                }
            });
        }
        assertEquals(0, count(publishedId));
        assertEquals(204, request(publishedId, token));
        assertEquals(1, count(publishedId));
    }
}
