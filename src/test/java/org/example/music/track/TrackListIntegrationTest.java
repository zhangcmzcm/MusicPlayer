package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.example.music.security.JwtTokenService;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.repository.TrackListRepository;
import org.example.music.track.service.TrackListParameters;
import org.example.music.track.service.TrackListService;
import org.example.music.track.service.TrackListSort;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class TrackListIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music").withUrlParam("rewriteBatchedStatements", "true");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired TrackRepository tracks;
    @Autowired MediaAssetRepository assets;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired TrackListService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean TrackListRepository listRepository;
    @MockitoSpyBean AudioStorage storage;
    private UserEntity user;
    private UserEntity admin;
    private final List<TrackEntity> seeded = new ArrayList<>();

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> STATEMENTS = ThreadLocal.withInitial(ArrayList::new);
        public static void clear() { STATEMENTS.get().clear(); }
        public static List<String> statements() { return List.copyOf(STATEMENTS.get()); }
        @Override public String inspect(String sql) {
            if (sql.stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("select")) {
                STATEMENTS.get().add(sql.toLowerCase(java.util.Locale.ROOT));
            }
            return sql;
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username", () -> "");
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", () -> SqlCapture.class.getName());
    }

    @BeforeEach
    void setup() {
        reset(listRepository,storage);
        tracks.deleteAll(); assets.deleteAll(); userRepository.deleteAll(); seeded.clear();
        user = userRepository.save(new UserEntity("list-owner","list-owner@example.com","test-hash",UserRole.USER));
        admin = userRepository.save(new UserEntity("list-admin","list-admin@example.com","test-hash",UserRole.ADMIN));
        LocalDateTime now = LocalDateTime.of(2026,1,1,0,0);
        var audio = assets.save(MediaAssetEntity.create(MediaAssetKind.AUDIO,"private-audio","audio", "audio/mpeg","a.mp3",1L,"a".repeat(64),now,user.getId()));
        var coverRecords = new ArrayList<MediaAssetEntity>();
        for (int i=0;i<120;i++) {
            coverRecords.add(MediaAssetEntity.create(MediaAssetKind.COVER,"private-covers","cover-"+i,"image/png","c.png",12L,"b".repeat(64),now,user.getId()));
        }
        var covers = assets.saveAll(coverRecords);
        var trackRecords = new ArrayList<TrackEntity>();
        for (int i=0;i<120;i++) {
            var track = TrackEntity.createDraft("Title "+(i%7),"Artist "+(i%4),null,null,null,null,audio.getId(),covers.get(i).getId(),"{\"private\":true}",now.plusSeconds(i%5),user.getId());
            if (i%3==1) { track.publish(user.getId(),now); }
            if (i%3==2) { track.archive(user.getId(),now); }
            trackRecords.add(track);
        }
        seeded.addAll(tracks.saveAll(trackRecords));
        SqlCapture.clear();
    }

    private String bearer(UserEntity actor,UserRole role) { return "Bearer "+issueToken(actor,role); }

    @Test
    void should_filter_content_and_totals_by_real_login_role() throws Exception {
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[*].status",org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("PUBLISHED"))))
                .andExpect(jsonPath("$.totalElements").value(40)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(admin,UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(120));
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(admin,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(40));
        for (String state : List.of("DRAFT","ARCHIVED","PUBLISHED")) {
            mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(admin,UserRole.ADMIN)).param("status",state))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(40))
                    .andExpect(jsonPath("$.items[*].status",org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(state))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"DRAFT","ARCHIVED"})
    void should_reject_hidden_filters_before_any_business_query(String state) throws Exception {
        for (UserEntity actor : List.of(user,admin)) {
            SqlCapture.clear();
            mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(actor,UserRole.USER)).param("status",state))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            assertEquals(1,SqlCapture.statements().size());
            assertTrue(SqlCapture.statements().getFirst().contains("users"));
        }
    }

    @Test
    void should_authenticate_and_validate_before_executing_query() throws Exception {
        mvc.perform(get("/api/v1/tracks").param("size","0")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)).param("status","DRAFT").param("size","0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String token = bearer(user,UserRole.USER);
        jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());
        mvc.perform(get("/api/v1/tracks").header("Authorization",token)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void should_return_empty_and_out_of_range_pages_with_accurate_totals() throws Exception {
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)).param("page","4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(40)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
        jdbc.update("update tracks set status='ARCHIVED' where status='PUBLISHED'");
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    @ParameterizedTest
    @CsvSource({"id,asc","id,desc","title,asc","title,desc","artistName,asc","artistName,desc","createdAt,asc","createdAt,desc"})
    void should_sort_before_pagination_with_same_direction_id_tiebreaker(String property,String direction) throws Exception {
        String column = Map.of("id","id","title","title","artistName","artist_name","createdAt","created_at").get(property);
        String order = column+" "+direction+(column.equals("id")?"":",id "+direction);
        var expected = jdbc.queryForList("select id from tracks where status='PUBLISHED' order by "+order+" limit 6",Long.class);
        for (int page=0;page<2;page++) {
            var result = mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER))
                    .param("sort",property+","+direction).param("size","3").param("page",String.valueOf(page))).andExpect(status().isOk());
            for(int i=0;i<3;i++) { result.andExpect(jsonPath("$.items["+i+"].id").value(expected.get(page*3+i))); }
        }
    }

    @ParameterizedTest
    @ValueSource(ints={1,20,100})
    void should_use_constant_three_business_queries_without_id3_or_minio(int size) throws Exception {
        var parameters = new TrackListParameters(0,size,new TrackListSort("id",false),null);
        var actor = new org.example.music.security.AuthenticatedUserPrincipal(admin.getId(),UserRole.ADMIN);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            SqlCapture.clear(); service.list(parameters,actor);
            assertEquals(3,SqlCapture.statements().size());
            assertTrue(SqlCapture.statements().stream().noneMatch(sql->sql.contains("id3_metadata")));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        SqlCapture.clear();
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(admin,UserRole.ADMIN)).param("size",String.valueOf(size)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(size));
        assertEquals(4,SqlCapture.statements().size());
        assertEquals(1,SqlCapture.statements().stream().filter(sql->sql.contains("users")).count());
        verifyNoInteractions(storage);
    }

    @Test
    void should_only_fail_for_corrupt_covers_on_current_visible_page() throws Exception {
        TrackEntity hidden = seeded.get(119);
        jdbc.update("update media_assets set kind='AUDIO' where id=?",hidden.getCoverAssetId());
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].cover.url").value("/api/v1/tracks/"+seeded.get(118).getId()+"/cover"));
        TrackEntity otherPage = seeded.get(1);
        jdbc.update("update media_assets set kind='AUDIO' where id=?",otherPage.getCoverAssetId());
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER))).andExpect(status().isOk());
        TrackEntity visible = seeded.get(118);
        jdbc.update("update media_assets set kind='AUDIO' where id=?",visible.getCoverAssetId());
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verifyNoInteractions(storage);
    }

    @Test
    void should_allow_exact_depth_boundary_and_return_accurate_empty_page() throws Exception {
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(admin,UserRole.ADMIN))
                        .param("page","100").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(120)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void should_skip_asset_query_for_absent_covers_and_preserve_database_on_repeat() throws Exception {
        jdbc.update("update tracks set cover_asset_id=null");
        for(int i=0;i<2;i++) {
            SqlCapture.clear();
            mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].cover").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.items[0].albumName").value(org.hamcrest.Matchers.nullValue()));
            assertEquals(3,SqlCapture.statements().size());
        }
        assertEquals(0L,tracks.findById(seeded.getFirst().getId()).orElseThrow().getVersion());
        assertEquals(121,assets.count());
        verifyNoInteractions(storage);
    }

    @Test
    @Timeout(30)
    void should_keep_content_and_count_on_one_repeatable_read_snapshot() throws Exception {
        long id = seeded.get(118).getId();
        var independent = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
        doAnswer(invocation -> {
            independent.update("update tracks set status='ARCHIVED' where id=?",id);
            return invocation.callRealMethod();
        }).when(listRepository).count(TrackStatus.PUBLISHED);
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(40))
                .andExpect(jsonPath("$.items[0].id").value(id));
        reset(listRepository);
        mvc.perform(get("/api/v1/tracks").header("Authorization",bearer(user,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(39));
    }

    private String issueToken(UserEntity user, UserRole loginRole) {
        return jwtTokenService.issue(new UserAuthorizationSnapshot(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole(),
                user.getTokenVersion()
        ), loginRole, LoginType.USERNAME_PASSWORD).tokenValue();
    }
}