package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfDockerAvailable
class TrackListQueryIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("list_plans").withUrlParam("rewriteBatchedStatements", "true");

    @Test
    void should_record_before_and_after_query_plans_and_preserve_existing_rows() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).target("7").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.update("insert into users(id,username,password_token,user_role) values(1,'plan-user','test-hash','ADMIN')");
        jdbc.update("insert into media_assets(id,kind,bucket,object_key,content_type,original_filename,size_bytes,sha256,created_at,created_by) values(1,'AUDIO','test','audio','audio/mpeg','audio.mp3',1,?,now(),1)", "a".repeat(64));
        var rows = new ArrayList<Object[]>();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int i = 0; i < 12000; i++) {
            String state = new String[]{"DRAFT", "PUBLISHED", "ARCHIVED"}[i % 3];
            rows.add(new Object[]{"Title " + (i % 101), "Artist " + (i % 71), state,
                    Timestamp.valueOf(now.plusSeconds(i % 31)), Timestamp.valueOf(now), 1, 1});
        }
        jdbc.batchUpdate("insert into tracks(title,artist_name,status,created_at,updated_at,created_by,updated_by,audio_asset_id,version) values(?,?,?,?,?,?,?,1,0)", rows);
        jdbc.execute("ANALYZE TABLE tracks");
        String before = plans(jdbc, "V7：原直接投影查询", false);
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        jdbc.execute("ANALYZE TABLE tracks");
        String after = plans(jdbc, "V8：索引分页 ID 加本页回表", true) + analyze(jdbc);
        assertEquals(12000, jdbc.queryForObject("select count(*) from tracks", Integer.class));
        assertEquals(4000, jdbc.queryForObject("select count(*) from tracks where status='PUBLISHED'", Integer.class));
        Path report = Path.of("build/reports/track-list-query-plans.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "# 歌曲列表真实 MySQL EXPLAIN\n\n数据：12000 行，三状态各 4000；重复标题、艺术家与时间。内容投影与业务查询相同，无 ID3。MySQL 8.4，无 FORCE INDEX。\n\n" + before + after);
    }

    private String analyze(JdbcTemplate jdbc) {
        var out = new StringBuilder("## V8 实际执行分析\n\nEXPLAIN 的 rows 为估计值；以下 TREE 记录 actual rows，核对 LIMIT 后回表与外层排序仅处理本页。耗时只代表测试环境，不作为 SLA。\n\n");
        for (boolean filtered : new boolean[]{false,true}) {
            for (String field : List.of("title","createdAt")) {
                int offset = filtered ? 100 : 10000;
                String sql = org.example.music.track.repository.TrackListRepository.contentSql(
                        new org.example.music.track.service.TrackListSort(field,true),filtered)
                        .replace(":status","'PUBLISHED'").replace(":size","20").replace(":offset",String.valueOf(offset));
                out.append("### ").append(filtered ? "PUBLISHED" : "全部").append(" / ").append(field).append(" / offset=").append(offset)
                        .append("\n\n```text\n").append(jdbc.queryForObject("EXPLAIN ANALYZE "+sql,String.class)).append("\n```\n\n");
                assertEquals(20,jdbc.queryForList(sql).size());
            }
        }
        return out.toString();
    }

    private String plans(JdbcTemplate jdbc, String heading, boolean deferred) {
        var out = new StringBuilder("## " + heading + "\n\n| 筛选 | 排序 | offset | table | type | key | rows | Extra |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String where : List.of("", " where status='PUBLISHED'")) {
            for (String field : List.of("id", "title", "artist_name", "created_at")) {
                for (String direction : List.of("asc", "desc")) {
                    for (int offset : new int[]{0, 10000}) {
                        String order = field + " " + direction + (field.equals("id") ? "" : ", id " + direction);
                        String sql = "select id,title,artist_name,album_name,duration_ms,status,cover_asset_id from tracks" + where + " order by " + order + " limit 20 offset " + offset;
                        if (deferred) {
                            String property = field.equals("artist_name") ? "artistName" : field.equals("created_at") ? "createdAt" : field;
                            sql = org.example.music.track.repository.TrackListRepository.contentSql(
                                    new org.example.music.track.service.TrackListSort(property, direction.equals("asc")), !where.isEmpty())
                                    .replace(":status", "'PUBLISHED'").replace(":size", "20").replace(":offset", String.valueOf(offset));
                        }
                        for (var plan : jdbc.queryForList("EXPLAIN " + sql)) {
                            out.append("| ").append(where.isEmpty() ? "全部" : "PUBLISHED").append(" | ").append(order).append(" | ").append(offset)
                                    .append(" | ").append(plan.get("table")).append(" | ").append(plan.get("type")).append(" | ").append(plan.get("key")).append(" | ").append(plan.get("rows")).append(" | ").append(plan.get("Extra")).append(" |\n");
                        }
                    }
                }
            }
            var plan = jdbc.queryForList("EXPLAIN select count(*) from tracks" + where).getFirst();
            out.append("| ").append(where.isEmpty() ? "全部" : "PUBLISHED").append(" | COUNT | — | tracks | ").append(plan.get("type"))
                    .append(" | ").append(plan.get("key")).append(" | ").append(plan.get("rows")).append(" | ").append(plan.get("Extra")).append(" |\n");
        }
        return out.append("\n").toString();
    }
}
