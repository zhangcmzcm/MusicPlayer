package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.music.track.repository.TrackListRepository;
import org.example.music.track.service.TrackListSort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfDockerAvailable
class TrackSearchQueryPlanIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("search_plans").withUrlParam("rewriteBatchedStatements", "true");

    private record Sample(long id, String title, String artist, String album, String status, LocalDateTime createdAt) {
        boolean matches(String term) {
            return title.contains(term) || artist.contains(term) || (album != null && album.contains(term));
        }
    }

    @Test
    void should_record_content_and_count_plans_for_ten_thousand_tracks() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        var named = new NamedParameterJdbcTemplate(jdbc);
        jdbc.update("insert into users(id,username,password_token,user_role) values(1,'search-plan-user','test-hash','ADMIN')");
        jdbc.update("insert into media_assets(id,kind,bucket,object_key,content_type,original_filename,size_bytes,sha256,created_at,created_by) values(1,'AUDIO','test','audio','audio/mpeg','audio.mp3',1,?,now(),1)", "a".repeat(64));
        var samples = new ArrayList<Sample>();
        var batch = new ArrayList<Object[]>();
        LocalDateTime baseline = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int index = 0; index < 10000; index++) {
            var sample = new Sample(index + 1L, "晴天现场 " + index % 101,
                    index % 2 == 0 ? "周杰伦" : "其他歌手", index % 5 == 0 ? null : "十一月的萧邦 November",
                    new String[]{"DRAFT", "PUBLISHED", "ARCHIVED"}[index % 3], baseline.plusSeconds(index % 31));
            samples.add(sample);
            batch.add(new Object[]{sample.id(), sample.title(), sample.artist(), sample.album(), sample.status(),
                    Timestamp.valueOf(sample.createdAt()), Timestamp.valueOf(baseline), 1, 1});
        }
        jdbc.batchUpdate("insert into tracks(id,title,artist_name,album_name,status,created_at,updated_at,created_by,updated_by,audio_asset_id,version) values(?,?,?,?,?,?,?,?,?,1,0)", batch);
        jdbc.execute("ANALYZE TABLE tracks");
        assertEquals(10000, jdbc.queryForObject("select count(*) from tracks", Integer.class));
        var report = new StringBuilder("# 歌曲搜索真实 MySQL 查询计划\n\nMySQL 8.4，10000 首，DRAFT 3334 首、PUBLISHED 3333 首、ARCHIVED 3333 首。搜索语句使用生产 SQL 工厂与 2000 毫秒预算。预期结果由固定样本在 Java 中独立筛选及排序。\n\n");
        var searches = List.of(List.of("晴"), List.of("周杰伦", "晴天"), List.of("晴", "天", "现", "场", "周"),
                List.of("不存在词XYZ"), List.of("November"));
        for (boolean filtered : new boolean[]{false, true}) {
            for (List<String> terms : searches) {
                for (String property : List.of("id", "title", "artistName", "createdAt")) {
                    for (boolean ascending : new boolean[]{true, false}) {
                        recordCase(named, samples, report, filtered, terms, new TrackListSort(property, ascending), 0, 20);
                    }
                }
            }
            for (List<String> terms : List.of(searches.get(0), searches.get(2))) {
                recordCase(named, samples, report, filtered, terms, new TrackListSort("id", false), 0, 100);
                recordCase(named, samples, report, filtered, terms, new TrackListSort("id", false), 10000, 100);
            }
        }
        Path output = Path.of("build/reports/track-search-query-plans.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString());
    }

    private void recordCase(NamedParameterJdbcTemplate jdbc, List<Sample> samples, StringBuilder report,
                            boolean filtered, List<String> terms, TrackListSort sort, int offset, int size) {
        Comparator<Sample> order = switch (sort.property()) {
            case "id" -> Comparator.comparingLong(Sample::id);
            case "title" -> Comparator.comparing(Sample::title).thenComparingLong(Sample::id);
            case "artistName" -> Comparator.comparing(Sample::artist).thenComparingLong(Sample::id);
            case "createdAt" -> Comparator.comparing(Sample::createdAt).thenComparingLong(Sample::id);
            default -> throw new IllegalArgumentException("Unexpected sort");
        };
        if (!sort.ascending()) {
            order = order.reversed();
        }
        List<Sample> expected = samples.stream().filter(sample -> !filtered || sample.status().equals("PUBLISHED"))
                .filter(sample -> terms.stream().allMatch(sample::matches)).sorted(order).toList();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("size", size);
        parameters.put("offset", offset);
        if (filtered) {
            parameters.put("status", "PUBLISHED");
        }
        for (int index = 0; index < terms.size(); index++) {
            parameters.put("q" + index, "%" + terms.get(index) + "%");
        }
        String content = TrackListRepository.contentSql(sort, filtered, terms.size(), 2000);
        String count = TrackListRepository.countSql(filtered, terms.size(), 2000);
        List<Long> ids = jdbc.query(content, parameters, (result, row) -> result.getLong("id"));
        assertEquals(expected.stream().skip(offset).limit(size).map(Sample::id).toList(), ids);
        assertEquals((long) expected.size(), jdbc.queryForObject(count, parameters, Long.class));
        report.append("## ").append(filtered ? "PUBLISHED" : "全部状态").append(" / ").append(terms)
                .append(" / ").append(sort.property()).append(sort.ascending() ? " ASC" : " DESC")
                .append(" / offset=").append(offset).append(" / size=").append(size)
                .append("\n\n匹配总数为 ").append(expected.size()).append("。\n\n");
        for (String sql : List.of(content, count)) {
            report.append("```sql\n").append(sql).append("\n```\n\n")
                    .append("```text\n").append(jdbc.queryForList("EXPLAIN " + sql, parameters))
                    .append("\n").append(jdbc.queryForObject("EXPLAIN ANALYZE " + sql, parameters, String.class))
                    .append("\n```\n\n");
        }
    }
}
