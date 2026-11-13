package org.example.music.favorite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.example.music.favorite.repository.FavoriteListRepository;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.user.domain.UserRole;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class FavoriteListIntegrationTest extends FavoriteIntegrationSupport {
    static final String PATH = "/api/v1/users/me/favorites";
    @MockitoSpyBean FavoriteListRepository favorites;
    @MockitoSpyBean MediaAssetRepository assets;
    @MockitoSpyBean AudioStorage storage;
    @MockitoSpyBean PlatformTransactionManager manager;
    @Autowired JwtEncoder encoder;

    public static class SqlCapture implements StatementInspector {
        static final List<String> SQL = new CopyOnWriteArrayList<>();
        @Override public String inspect(String sql) { SQL.add(sql); return sql; }
    }
    @DynamicPropertySource
    static void capture(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", () -> SqlCapture.class.getName());
    }
    @BeforeEach
    void resetSpies() { reset(favorites,assets,storage,manager); SqlCapture.SQL.clear(); }

    void favorite(int owner, long track, String time) {
        jdbc.update("insert into user_favorites values(?,?,cast(? as datetime(6)),cast(? as datetime(6)),?,?)",owner,track,time,time,owner,owner);
    }
    long cover(long track) {
        long id = insert("insert into media_assets(kind,bucket,object_key,content_type,original_filename,size_bytes,sha256,created_at,created_by) values('COVER','absent-test-bucket',?,'image/png','test.png',15,?,UTC_TIMESTAMP(6),?)",
                java.util.UUID.randomUUID().toString(),"a".repeat(64),adminId);
        jdbc.update("update tracks set cover_asset_id=? where id=?",id,track);
        return id;
    }

    @ParameterizedTest
    @CsvSource({"USER,USER,1", "ADMIN,ADMIN,3", "ADMIN,USER,1"})
    void should_limit_content_and_count_to_owner_and_login_role(UserRole account, UserRole login, int total) throws Exception {
        int owner = account == UserRole.USER ? userId : adminId;
        for (long id : List.of(publishedId,draftId,archivedId)) {
            favorite(owner,id,"2026-11-12 08:30:00.123456");
        }
        favorite(owner == userId ? adminId : userId,createTrack("PUBLISHED"),"2026-11-12 08:30:00.123456");
        jdbc.update("update tracks set created_by=? where id in (?,?)",owner,draftId,archivedId);
        mvc.perform(get(PATH).header("Authorization",bearer(owner,login)).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(total)).andExpect(jsonPath("$.totalPages").value(total));
        mvc.perform(get(PATH).header("Authorization",bearer(owner,login)).param("status","PUBLISHED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(publishedId))
                .andExpect(jsonPath("$.totalElements").value(1));
        for (String state : List.of("DRAFT","ARCHIVED")) {
            var request = mvc.perform(get(PATH).header("Authorization",bearer(owner,login)).param("status",state));
            if (login == UserRole.ADMIN) request.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
            else request.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void should_preserve_empty_and_out_of_range_metadata_and_ignore_forged_headers() throws Exception {
        favorite(adminId,publishedId,"2026-11-12 08:30:00.123456");
        favorite(userId,draftId,"2026-11-12 08:30:00.123456");
        String token = bearer(userId,UserRole.USER);
        mvc.perform(get(PATH).header("Authorization",token).header("X-User-Id",adminId).header("X-Role","ADMIN")
                        .contentType("application/json").content("{\"userId\":"+adminId+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
        favorite(userId,publishedId,"2026-11-12 08:30:00.123456");
        mvc.perform(get(PATH).header("Authorization",token).param("page","100").param("size","100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(100)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get(PATH).header("Authorization",token).param("userId",String.valueOf(adminId)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).header("Authorization",token).param("status","DRAFT").param("page","-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("page","-1")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "title", "artistName", "createdAt", "favoritedAt"})
    void should_apply_stable_sort_before_paging(String field) throws Exception {
        long second = createTrack("PUBLISHED");
        for (long id : List.of(publishedId,second)) {
            favorite(userId,id,"2026-11-12 08:30:00.123456");
            jdbc.update("update tracks set created_at='2026-01-01' where id=?",id);
        }
        String token = bearer(userId,UserRole.USER);
        for (String direction : List.of("asc","desc")) {
            for (int page = 0; page < 2; page++) {
                long expected = (direction.equals("asc") == (page == 0)) ? publishedId : second;
                mvc.perform(get(PATH).header("Authorization",token).param("size","1").param("page",String.valueOf(page)).param("sort",field+","+direction))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(expected))
                        .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.hasNext").value(page == 0));
            }
        }
    }

    @Test
    void should_preserve_first_time_and_refresh_after_cancel_and_refavorite() throws Exception {
        favorite(userId,publishedId,"2026-01-01 00:00:00.123456");
        String token = bearer(userId,UserRole.USER);
        mvc.perform(put(PATH+"/"+publishedId).header("Authorization",token)).andExpect(status().isNoContent());
        mvc.perform(get(PATH).header("Authorization",token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].favoritedAt").value("2026-01-01T00:00:00.123456Z"));
        long another = createTrack("PUBLISHED");
        favorite(userId,another,"2026-02-01 00:00:00.123456");
        mvc.perform(get(PATH).header("Authorization",token)).andExpect(jsonPath("$.items[0].id").value(another));
        mvc.perform(delete(PATH+"/"+publishedId).header("Authorization",token)).andExpect(status().isNoContent());
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        mvc.perform(put(PATH+"/"+publishedId).header("Authorization",token)).andExpect(status().isNoContent());
        Instant after = Instant.now();
        String body = mvc.perform(get(PATH).header("Authorization",token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(publishedId)).andReturn().getResponse().getContentAsString();
        Instant actual = Instant.parse(new ObjectMapper().readTree(body).path("items").get(0).path("favoritedAt").asText());
        assertFalse(actual.isBefore(before)); assertFalse(actual.isAfter(after));
    }

    @ParameterizedTest
    @ValueSource(ints = {1,20,100})
    void should_batch_covers_with_at_most_three_business_queries_and_no_storage_calls(int size) throws Exception {
        long asset = cover(publishedId);
        for (int i=0;i<101;i++) {
            long id = i==0 ? publishedId : createTrack("PUBLISHED");
            jdbc.update("update tracks set cover_asset_id=? where id=?",asset,id);
            favorite(userId,id,"2026-11-12 08:30:00.123456");
        }
        String token = bearer(userId,UserRole.USER);
        SqlCapture.SQL.clear(); clearInvocations(assets,storage);
        mvc.perform(get(PATH).header("Authorization",token).param("size",String.valueOf(size)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(size))
                .andExpect(jsonPath("$.totalElements").value(101));
        var sql = SqlCapture.SQL.stream().filter(s -> s.contains("user_favorites") || s.contains("media_assets")).toList();
        assertEquals(3,sql.size(),sql.toString());
        assertTrue(sql.stream().noneMatch(s -> s.contains("id3_metadata")));
        verify(assets).findListCovers(java.util.Set.of(asset));
        verifyNoInteractions(storage);
    }

    @Test
    void should_only_fail_for_invalid_covers_on_current_visible_page() throws Exception {
        long visibleAsset = cover(publishedId);
        long hiddenAsset = cover(draftId);
        favorite(userId,publishedId,"2026-11-12 08:30:00.123456");
        favorite(userId,draftId,"2026-11-12 09:30:00.123456");
        jdbc.update("update media_assets set content_type='text/plain' where id=?",hiddenAsset);
        String token = bearer(userId,UserRole.USER);
        mvc.perform(get(PATH).header("Authorization",token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cover.url").value("/api/v1/tracks/"+publishedId+"/cover"));
        jdbc.update("update tracks set status='PUBLISHED' where id=?",draftId);
        mvc.perform(get(PATH).header("Authorization",token).param("sort","id,asc").param("size","1"))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).header("Authorization",token)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        jdbc.update("update tracks set cover_asset_id=null where id in (?,?)",publishedId,draftId);
        SqlCapture.SQL.clear(); clearInvocations(assets);
        mvc.perform(get(PATH).header("Authorization",token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cover").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(2,SqlCapture.SQL.stream().filter(s -> s.contains("user_favorites")).count());
        verifyNoInteractions(assets,storage);
        assertTrue(visibleAsset>0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"content","count","cover","commit"})
    void should_return_safe_503_for_each_query_or_transaction_failure(String phase) throws Exception {
        favorite(userId,publishedId,"2026-11-12 08:30:00.123456"); cover(publishedId);
        String token = bearer(userId,UserRole.USER);
        var fault = new DataAccessResourceFailureException("private SQL");
        switch (phase) {
            case "content" -> doThrow(fault).when(favorites).findRows(anyInt(),any(),anyInt(),anyInt(),any());
            case "count" -> doThrow(fault).when(favorites).count(anyInt(),any());
            case "cover" -> doThrow(fault).when(assets).findListCovers(anyCollection());
            case "commit" -> doAnswer(call -> { call.callRealMethod(); throw new TransactionSystemException("private commit"); })
                    .when(manager).commit(any(TransactionStatus.class));
        }
        try {
            mvc.perform(get(PATH).header("Authorization",token)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.detail").value("歌曲收藏暂时无法完成"))
                    .andExpect(header().string("Cache-Control","no-store"));
        } finally { reset(favorites,assets,manager); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED","WRONG_SIGNATURE","MISSING_ROLE","MISSING_VERSION","INVALID_SUBJECT","ISSUER","AUDIENCE"})
    void should_reject_invalid_signed_tokens_before_business_query(String kind) throws Exception {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(kind.equals("ISSUER") ? "other" : "music-api")
                .audience(List.of(kind.equals("AUDIENCE") ? "other" : "music-client"))
                .subject(kind.equals("INVALID_SUBJECT") ? "0" : String.valueOf(userId)).issuedAt(now.minusSeconds(300))
                .expiresAt(kind.equals("EXPIRED") ? now.minusSeconds(120) : now.plusSeconds(300));
        if (!kind.equals("MISSING_ROLE")) claims.claim("role","USER");
        if (!kind.equals("MISSING_VERSION")) claims.claim("tokenVersion",0L);
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims.build())).getTokenValue();
        if (kind.equals("WRONG_SIGNATURE")) {
            int start = token.lastIndexOf('.')+1;
            token = token.substring(0,start)+(token.charAt(start)=='A'?'B':'A')+token.substring(start+1);
        }
        mvc.perform(get(PATH).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites,assets);
    }

    @Test
    void should_reject_revoked_account_role_and_version() throws Exception {
        String user = bearer(userId,UserRole.USER);
        jdbc.update("update users set token_version=token_version+1 where id=?",userId);
        mvc.perform(get(PATH).header("Authorization",user)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        String admin = bearer(adminId,UserRole.ADMIN);
        jdbc.update("update users set user_role='USER' where id=?",adminId);
        mvc.perform(get(PATH).header("Authorization",admin)).andExpect(status().isUnauthorized());
        user = bearer(userId,UserRole.USER);
        jdbc.update("delete from users where id=?",userId);
        mvc.perform(get(PATH).header("Authorization",user)).andExpect(status().isUnauthorized());
        verifyNoInteractions(favorites);
    }

    @Test
    void should_publish_accurate_openapi_without_changing_write_contracts() throws Exception {
        String json = mvc.perform(get("/v3/api-docs").header("Authorization",bearer(userId,UserRole.USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var root = new ObjectMapper().readTree(json);
        var operation = root.path("paths").path(PATH).path("get");
        assertFalse(operation.has("requestBody"));
        assertEquals(4,operation.path("parameters").size());
        for (String code : List.of("200","400","401","403","500","503")) assertTrue(operation.path("responses").has(code),code);
        var properties = root.path("components").path("schemas").path("FavoriteListItemResponse").path("properties");
        assertEquals("date-time",properties.path("favoritedAt").path("format").asText());
        assertTrue(properties.has("cover")); assertFalse(properties.has("track"));
        var writes = root.path("paths").path(PATH+"/{trackId}");
        assertTrue(writes.path("put").path("responses").has("404"));
        assertFalse(writes.path("delete").path("responses").path("204").has("content"));
    }
}
