package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.example.music.favorite.repository.FavoriteListRepository;
import org.example.music.favorite.service.FavoriteListSort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FavoriteListQueryPlanIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withUrlParam("rewriteBatchedStatements", "true");

    @Test
    void should_upgrade_without_changing_rows_and_record_real_query_plans() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("9").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("insert into users(id,username,password_token,user_role) values(1,'plans','test-hash','ADMIN'),(2,'other','test-hash','USER'),(3,'sparse','test-hash','USER')");
        jdbc.update("insert into media_assets(id,kind,bucket,object_key,content_type,original_filename,size_bytes,sha256,created_at,created_by) values(1,'AUDIO','test','audio','audio/mpeg','test.mp3',1,?,UTC_TIMESTAMP(6),1)", "a".repeat(64));
        var tracks = new ArrayList<Object[]>();
        var favorites = new ArrayList<Object[]>();
        for (int i = 1; i <= 12000; i++) {
            tracks.add(new Object[]{i, "Title " + i % 101, "Artist " + i % 71,
                    new String[]{"DRAFT", "PUBLISHED", "ARCHIVED"}[i % 3]});
            favorites.add(new Object[]{1, i});
            if (i % 2 == 0) favorites.add(new Object[]{2, i});
        }
        jdbc.batchUpdate("insert into tracks(id,title,artist_name,status,audio_asset_id,created_at,updated_at,created_by,updated_by,version) values(?,?,?,?,1,'2026-01-01','2026-01-01',1,1,0)", tracks);
        jdbc.batchUpdate("insert into user_favorites(user_id,track_id,created_at,updated_at,created_by,updated_by) values(?,?,'2026-11-12 08:30:00.123456','2026-11-12 08:30:00.123456',1,1)", favorites);
        jdbc.update("insert into user_favorites values(3,1,'2026-01-01','2026-01-01',3,3)");
        var before = jdbc.queryForList("select * from user_favorites order by user_id,track_id");
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        assertEquals(before, jdbc.queryForList("select * from user_favorites order by user_id,track_id"));
        assertEquals(List.of("user_id", "created_at", "track_id"), jdbc.queryForList(
                "select column_name from information_schema.statistics where table_schema=database() and table_name='user_favorites' and index_name='ix_user_favorites_user_created_track' order by seq_in_index", String.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from information_schema.referential_constraints where constraint_schema=database() and table_name='user_favorites' and delete_rule='CASCADE'", Integer.class));
        jdbc.execute("ANALYZE TABLE user_favorites, tracks");
        var report = new StringBuilder("# 本人收藏查询真实 MySQL 8.4 执行计划\n\n本人12000条，其他用户6000条；本人三状态各4000条，重复排序值。无 FORCE INDEX。计数与部分排序成本随候选量增长，耗时不作为 SLA。\n");
        for (boolean filtered : List.of(false, true)) {
            for (String field : List.of("id", "title", "artistName", "createdAt", "favoritedAt")) {
                for (boolean ascending : List.of(false, true)) {
                    for (int offset : new int[]{0, 10000}) {
                        String sql = FavoriteListRepository.contentSql(new FavoriteListSort(field, ascending), filtered)
                                .replace(":userId", "1").replace(":status", "'PUBLISHED'")
                                .replace(":size", "20").replace(":offset", Integer.toString(offset));
                        report.append("\n## ").append(field).append('/').append(ascending).append('/').append(filtered).append('/').append(offset)
                                .append("\n\n").append(jdbc.queryForList("EXPLAIN " + sql)).append('\n');
                        assertEquals(filtered && offset == 10000 ? 0 : 20, jdbc.queryForList(sql).size());
                        if (!ascending && (field.equals("favoritedAt") || field.equals("title"))) {
                            report.append("\n```text\n").append(jdbc.queryForObject("EXPLAIN ANALYZE " + sql, String.class)).append("\n```\n");
                        }
                    }
                }
            }
            String countSql = "select count(*) from user_favorites f straight_join tracks t on t.id=f.track_id where f.user_id=1"
                    + (filtered ? " and t.status='PUBLISHED'" : "");
            assertEquals(filtered ? 4000L : 12000L, jdbc.queryForObject(countSql, Long.class));
            report.append("\n## count / ").append(filtered).append("\n\n```text\n")
                    .append(jdbc.queryForObject("EXPLAIN ANALYZE " + countSql, String.class)).append("\n```\n");
        }
        for (String field : List.of("id", "title", "artistName", "createdAt", "favoritedAt")) {
            String sparseSql = FavoriteListRepository.contentSql(new FavoriteListSort(field,false),false)
                    .replace(":userId","3").replace(":size","20").replace(":offset","0");
            var plan = jdbc.queryForList("EXPLAIN " + sparseSql);
            assertEquals("f",plan.getFirst().get("table"));
            assertNotEquals("ALL",plan.getFirst().get("type"));
            assertEquals(1,jdbc.queryForList(sparseSql).size());
            report.append("\n## 稀疏收藏 / ").append(field).append("\n\n```text\n")
                    .append(jdbc.queryForObject("EXPLAIN ANALYZE " + sparseSql,String.class)).append("\n```\n");
        }
        Path path = Path.of("build/reports/favorite-list-query-plans.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, report);
    }
}
