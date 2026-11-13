package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.concurrent.*;
import org.example.music.favorite.repository.FavoriteListRepository;
import org.example.music.favorite.service.*;
import org.example.music.track.domain.TrackStatus;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class FavoriteListSnapshotIntegrationTest extends FavoriteIntegrationSupport {
    @Autowired FavoriteListQueryService query;
    @MockitoSpyBean FavoriteListRepository favorites;

    @ParameterizedTest
    @ValueSource(strings = {"cancel","archive","delete","put"})
    @Timeout(45)
    void should_keep_single_response_snapshot_while_writes_complete(String operation) throws Exception {
        jdbc.update("insert into user_favorites values(?,?,'2026-01-01','2026-01-01',?,?)",userId,publishedId,userId,userId);
        long coverId = insert("insert into media_assets(kind,bucket,object_key,content_type,original_filename,size_bytes,sha256,created_at,created_by) values('COVER','test','cover','image/png','test.png',15,?,UTC_TIMESTAMP(6),?)","a".repeat(64),adminId);
        jdbc.update("update tracks set cover_asset_id=? where id=?",coverId,publishedId);
        String token = bearer(userId,UserRole.USER);
        var params = new FavoriteListParameters(0,20,new FavoriteListSort("favoritedAt",false),null);
        var read = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            read.countDown();
            if (!resume.await(20,TimeUnit.SECONDS)) throw new AssertionError("snapshot reader timed out");
            return result;
        }).when(favorites).findRows(eq(userId),eq(TrackStatus.PUBLISHED),eq(0),eq(20),any());
        try {
            var response = workers.submit(() -> query.query(userId,params,TrackStatus.PUBLISHED));
            assertTrue(read.await(15,TimeUnit.SECONDS));
            var write = workers.submit(() -> {
                switch (operation) {
                    case "cancel" -> mvc.perform(delete("/api/v1/users/me/favorites/"+publishedId).header("Authorization",token)).andExpect(status().isNoContent());
                    case "put" -> mvc.perform(put("/api/v1/users/me/favorites/"+publishedId).header("Authorization",token)).andExpect(status().isNoContent());
                    case "archive" -> transaction().executeWithoutResult(s -> jdbc.update("update tracks set status='ARCHIVED' where id=?",publishedId));
                    case "delete" -> transaction().executeWithoutResult(s -> {
                        jdbc.update("delete from tracks where id=?",publishedId);
                        jdbc.update("delete from media_assets where id=?",coverId);
                    });
                }
                return null;
            });
            write.get(10,TimeUnit.SECONDS);
            resume.countDown();
            var page = response.get(10,TimeUnit.SECONDS);
            assertEquals(1,page.totalElements());
            assertEquals(1,page.items().size());
            assertEquals("PUBLISHED",page.items().getFirst().status());
            assertNotNull(page.items().getFirst().cover());
            assertEquals("/api/v1/tracks/"+publishedId+"/cover",page.items().getFirst().cover().url());
            reset(favorites);
            var next = query.query(userId,params,TrackStatus.PUBLISHED);
            assertEquals(operation.equals("put") ? 1 : 0,next.totalElements());
            assertEquals(operation.equals("put") ? 1 : 0,next.items().size());
        } finally {
            resume.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));
            reset(favorites);
        }
    }
}
