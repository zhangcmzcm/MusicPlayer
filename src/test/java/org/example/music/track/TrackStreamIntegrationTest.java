package org.example.music.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.security.JwtTokenService;
import org.example.music.storage.domain.AudioStorage;
import org.example.music.storage.domain.AudioStreamStorage;
import org.example.music.storage.domain.VerifiedAudioStream;
import org.example.music.track.domain.MediaAssetEntity;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackEntity;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackRepository;
import org.example.music.track.service.AudioTestFixtures;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginType;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.tomcat.connection-timeout=1s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
@EnabledIfEnvironmentVariable(named="MINIO_INTEGRATION_TESTS",matches="true")
class TrackStreamIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse("bitnamilegacy/minio@sha256:451fe6858cb770cc9d0e77ba811ce287420f781c7c1b806a386f6896471a349c"))
            .withEnv("MINIO_ROOT_USER","test-access-key").withEnv("MINIO_ROOT_PASSWORD","test-secret-key").withExposedPorts(9000);
    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired TrackRepository tracks;
    @Autowired MediaAssetRepository assets;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired JdbcTemplate jdbc;
    @Autowired S3Client s3;
    @Autowired ConfigurableApplicationContext context;
    @MockitoSpyBean AudioStorage storage;
    @MockitoSpyBean AudioStreamStorage reader;
    @LocalServerPort int port;
    @TempDir Path directory;
    private UserEntity owner;
    private UserEntity admin;
    private TrackEntity track;
    private byte[] bytes;
    private String key;
    private String digest;

    @DynamicPropertySource static void containers(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        r.add("spring.data.redis.username",()->"");r.add("spring.data.redis.password",()->"");
        r.add("app.storage.endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));
        r.add("app.storage.audio-bucket",()->"stream-test-audio");r.add("app.storage.connect-timeout",()->"1s");r.add("app.storage.read-timeout",()->"3s");
        r.add("app.storage.stream.max-connections",()->"2");
    }
    @BeforeEach void setup() throws Exception {
        reset(storage,reader);tracks.deleteAll();assets.deleteAll();userRepository.deleteAll();
        if(s3.listBuckets().buckets().stream().noneMatch(b->b.name().equals("stream-test-audio"))) { s3.createBucket(b->b.bucket("stream-test-audio")); }
        owner=userRepository.save(new UserEntity("stream-owner","stream-owner@example.com","hash",UserRole.USER));
        admin=userRepository.save(new UserEntity("stream-admin","stream-admin@example.com","hash",UserRole.ADMIN));seed("mp3",1);
    }
    @AfterEach void clear() { reset(storage,reader); }
    private void seed(String format,int repeats) throws Exception {
        byte[] unit;
        if(format.equals("flac")) { try(var fixture=getClass().getResourceAsStream("/fixtures/audio/silence.flac")) { unit=Objects.requireNonNull(fixture).readAllBytes(); } }
        else { unit=Files.readAllBytes(AudioTestFixtures.minimalMp3(directory)); }
        bytes=new byte[unit.length*repeats];for(int i=0;i<repeats;i++) { System.arraycopy(unit,0,bytes,i*unit.length,unit.length); }
        digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));key="audio/"+UUID.randomUUID()+"."+format;
        String type=format.equals("flac")?"audio/flac":"audio/mpeg";
        storage.put("stream-test-audio",key,new ByteArrayInputStream(bytes),bytes.length,type,digest);
        var now=LocalDateTime.of(2026,10,1,0,0);
        var audio=assets.save(MediaAssetEntity.create(MediaAssetKind.AUDIO,"stream-test-audio",key,type,"audio."+format,(long)bytes.length,digest,now,owner.getId()));
        var entity=TrackEntity.createDraft("title","artist",null,null,null,null,audio.getId(),null,"{}",now,owner.getId());entity.publish(owner.getId(),now);track=tracks.save(entity);
        clearInvocations(storage,reader);
    }
    private String path() { return "/api/v1/tracks/"+track.getId()+"/stream"; }
    private String bearer(UserEntity user,UserRole role) { return "Bearer "+issueToken(user,role); }
    private String tag() { return "\""+digest+"\""; }

    @ParameterizedTest @ValueSource(strings={"mp3","flac"})
    void should_read_real_audio_and_ranges_without_database_transaction(String format) throws Exception {
        seed(format,1);
        doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return i.callRealMethod();}).when(storage).head(anyString(),anyString());
        doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return i.callRealMethod();}).when(reader).open(any(),any());
        mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(header().string("ETag",tag()))
                .andExpect(content().contentType(format.equals("mp3")?"audio/mpeg":"audio/flac"));
        for(String range:List.of("bytes=0-9","bytes=5-","bytes=-3","bytes=0-99999999999999999999999")) {
            int start=range.equals("bytes=5-")?5:range.equals("bytes=-3")?bytes.length-3:0;
            int end=range.equals("bytes=0-9")?9:bytes.length-1;
            mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("Range",range))
                    .andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(bytes,start,end+1)))
                    .andExpect(header().string("Content-Range","bytes "+start+"-"+end+"/"+bytes.length));
        }
        clearInvocations(reader);
        mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("If-None-Match","W/"+tag()).header("Range","bytes=bad"))
                .andExpect(status().isNotModified()).andExpect(content().string(""));verifyNoInteractions(reader);
        mvc.perform(head(path()).header("Authorization",bearer(owner,UserRole.USER)).header("Range","bytes=bad"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Length",String.valueOf(bytes.length))).andExpect(content().string(""));verifyNoInteractions(reader);
        assertEquals(0L,tracks.findById(track.getId()).orElseThrow().getVersion());
    }
    @ParameterizedTest @EnumSource(TrackStatus.class)
    void should_enforce_real_jwt_visibility_for_each_request(TrackStatus state) throws Exception {
        jdbc.update("update tracks set status=? where id=?",state.name(),track.getId());
        mvc.perform(get(path()).header("Authorization",bearer(admin,UserRole.ADMIN)).header("Range","bytes=0-0")).andExpect(status().isPartialContent());
        for(UserEntity user:List.of(owner,admin)) {
            var result=mvc.perform(get(path()).header("Authorization",bearer(user,UserRole.USER)).header("Range","bytes=99999999999-"));
            if(state==TrackStatus.PUBLISHED) { result.andExpect(status().isRequestedRangeNotSatisfiable()).andExpect(header().string("Content-Range","bytes */"+bytes.length)); }
            else { result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND")).andExpect(header().doesNotExist("Content-Range")); }
        }
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
    }
    @Test void should_check_revocation_before_conditional_or_range_processing() throws Exception {
        String token=bearer(owner,UserRole.USER);jdbc.update("update users set token_version=token_version+1 where id=?",owner.getId());
        mvc.perform(get(path()).header("Authorization",token).header("If-None-Match","*")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        verifyNoInteractions(storage,reader);
    }
    @Test void should_handle_if_range_and_unsupported_ranges() throws Exception {
        mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("Range","bytes=0-0").header("If-Range",tag()))
                .andExpect(status().isPartialContent()).andExpect(content().bytes(new byte[]{bytes[0]}));
        for(String condition:List.of("W/"+tag(),"\"old\"")) {
            mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("Range","bytes=bad").header("If-Range",condition))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        }
        for(String range:List.of("bytes=0-1,5-6","pages=1-2")) {
            mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("Range",range)).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        }
    }
    @Test void should_not_return_304_for_deleted_or_inconsistent_object() throws Exception {
        storage.delete("stream-test-audio",key);
        mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("If-None-Match","*"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Track not found: "+track.getId()));
        storage.put("stream-test-audio",key,new ByteArrayInputStream(bytes),bytes.length,"audio/flac",digest);
        mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("If-None-Match","*"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AUDIO_STORAGE_INCONSISTENT"));
    }
    @ParameterizedTest @ValueSource(strings={"delete","change"})
    void should_detect_changes_between_head_and_actual_get(String fault) throws Exception {
        doAnswer(i->{
            if(fault.equals("delete")) { s3.deleteObject(b->b.bucket("stream-test-audio").key(key)); }
            else { s3.putObject(b->b.bucket("stream-test-audio").key(key).contentType("audio/flac").metadata(Map.of("sha256",digest)),software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes)); }
            return i.callRealMethod();
        }).when(reader).open(any(),any());
        var result=mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("Range","bytes=0-9"));
        if(fault.equals("delete")) { result.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRACK_NOT_FOUND")); }
        else { result.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AUDIO_STORAGE_INCONSISTENT")); }
    }
    @Test void should_return_503_when_minio_pauses_even_with_matching_condition() throws Exception {
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER)).header("If-None-Match","*"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MEDIA_STORAGE_UNAVAILABLE"));
        } finally { MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec(); }
    }
    @Test void should_return_internal_error_for_bad_local_audio_asset() throws Exception {
        jdbc.update("update media_assets set kind='COVER' where id=?",track.getAudioAssetId());
        mvc.perform(get(path()).header("Authorization",bearer(owner,UserRole.USER))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verifyNoInteractions(storage,reader);
    }

    private HttpURLConnection connect() throws Exception {
        var c=(HttpURLConnection)URI.create("http://localhost:"+port+path()).toURL().openConnection();c.setRequestProperty("Authorization",bearer(owner,UserRole.USER));c.setConnectTimeout(3000);c.setReadTimeout(5000);return c;
    }
    @Test void should_stream_real_audio_over_actual_http() throws Exception {
        var c=connect();try { assertEquals(200,c.getResponseCode());try(var in=c.getInputStream()) { assertArrayEquals(bytes,in.readAllBytes()); } } finally { c.disconnect(); }
    }
    private CountDownLatch wrapStream(boolean fail,AtomicBoolean completed,AtomicInteger consumed) {
        var closed=new CountDownLatch(1);
        doAnswer(i->{
            VerifiedAudioStream original=(VerifiedAudioStream)i.callRealMethod();
            var input=new FilterInputStream(original.content()) {
                @Override public int read(byte[] b,int offset,int length) throws IOException {
                    if(fail && consumed.get()>=16384) { throw new IOException("injected failure"); }
                    if(!fail) { try { Thread.sleep(20); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException("interrupted"); } }
                    int n=in.read(b,offset,length);if(n>0)consumed.addAndGet(n);return n;
                }
            };
            return new VerifiedAudioStream() {
                public InputStream content() { return input; }
                public long contentLength() { return original.contentLength(); }
                public void completed() { completed.set(true);original.completed(); }
                public void close() throws IOException { try { original.close(); } finally { closed.countDown(); } }
            };
        }).when(reader).open(any(),any());return closed;
    }
    @Test @Timeout(30)
    void should_abort_committed_http_transfer_without_appending_json() throws Exception {
        seed("mp3",16);var completed=new AtomicBoolean();var consumed=new AtomicInteger();var closed=wrapStream(true,completed,consumed);
        var c=connect();var received=new ByteArrayOutputStream();
        try {
            assertEquals(200,c.getResponseCode());try(var in=c.getInputStream()) { in.transferTo(received); } catch(IOException expected) { }
            assertTrue(received.size()>0 && received.size()<bytes.length);assertArrayEquals(Arrays.copyOf(bytes,received.size()),received.toByteArray());
            assertTrue(closed.await(5,TimeUnit.SECONDS));assertFalse(completed.get());
        } finally { c.disconnect(); }
    }
    private Socket socket(boolean readFirst) throws Exception {
        var socket=new Socket();socket.setReceiveBufferSize(1024);socket.setSoTimeout(5000);socket.setSoLinger(true,0);socket.connect(new InetSocketAddress("127.0.0.1",port));
        String request="GET "+path()+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: "+bearer(owner,UserRole.USER)+"\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
        if(readFirst) { assertTrue(socket.getInputStream().readNBytes(1024).length>0); }return socket;
    }
    @Test @Timeout(30)
    void should_abort_real_minio_source_when_http_client_disconnects() throws Exception {
        seed("mp3",16);var completed=new AtomicBoolean();var consumed=new AtomicInteger();var closed=wrapStream(false,completed,consumed);
        try(var socket=socket(true)) { }
        assertTrue(closed.await(5,TimeUnit.SECONDS));assertFalse(completed.get());assertTrue(consumed.get()<bytes.length);
        var c=connect();try { assertEquals(200,c.getResponseCode());try(var in=c.getInputStream()) { assertArrayEquals(bytes,in.readAllBytes()); } } finally { c.disconnect(); }
    }
    @Test @Timeout(30)
    void should_release_stream_when_client_stops_reading_with_finite_tomcat_write_timeout() throws Exception {
        seed("mp3",512);var completed=new AtomicBoolean();var consumed=new AtomicInteger();var closed=wrapStream(false,completed,consumed);
        try(var socket=socket(false)) { assertTrue(closed.await(8,TimeUnit.SECONDS),"blocked socket write must time out"); }
        assertFalse(completed.get());assertTrue(consumed.get()<bytes.length);
        Object webServer=context.getClass().getMethod("getWebServer").invoke(context);
        Object tomcat=webServer.getClass().getMethod("getTomcat").invoke(webServer);
        Object service=tomcat.getClass().getMethod("getService").invoke(tomcat);
        Object[] connectors=(Object[])service.getClass().getMethod("findConnectors").invoke(service);
        for(Object connector:connectors) {
            Object handler=connector.getClass().getMethod("getProtocolHandler").invoke(connector);
            assertTrue(((Number)handler.getClass().getMethod("getConnectionTimeout").invoke(handler)).intValue()>0);
            assertTrue(((Number)handler.getClass().getMethod("getMaxThreads").invoke(handler)).intValue()>0);
            assertTrue(((Number)handler.getClass().getMethod("getMaxConnections").invoke(handler)).intValue()>0);
        }
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