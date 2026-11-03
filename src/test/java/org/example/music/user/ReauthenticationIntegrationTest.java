package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.LoginType;
import org.example.music.user.email.*;
import org.example.music.user.repository.*;
import org.example.music.user.service.ReauthenticationSnapshotReader;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Testcontainers @EnabledIfDockerAvailable
class ReauthenticationIntegrationTest {
    static final String PATH="/api/v1/users/me/reauthentications";
    static final String SEND="/api/v1/users/me/reauthentication-email-requests";
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReauthenticationSnapshotReader reader;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean PasswordHasher hasher;
    @MockitoSpyBean JwtTokenService tokens;
    @MockitoSpyBean ReauthenticationStore store;
    @MockitoBean ReauthenticationEmailSender mail;
    @MockitoBean(name="authenticationClock") Clock clock;
    final AtomicReference<Instant> now=new AtomicReference<>();
    final AtomicReference<ReauthenticationEmailMessage> sent=new AtomicReference<>();
    final JsonMapper json=JsonMapper.builder().build();

    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);registry.add("spring.datasource.username",MYSQL::getUsername);registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));registry.add("spring.data.redis.username",()->"default");registry.add("spring.data.redis.password",()->"");registry.add("spring.data.redis.database",()->"0");
    }
    @BeforeEach void resetData(){
        now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));when(clock.instant()).thenAnswer(i->now.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        users.deleteAll();var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);sent.set(null);doAnswer(i->{sent.set(i.getArgument(0));return null;}).when(mail).send(any());
    }
    UserEntity save(UserRole role,String email,String password){return users.saveAndFlush(new UserEntity("u"+UUID.randomUUID().toString().replace("-", ""),email,hasher.hash(password),role));}
    String token(UserEntity user,UserRole role,boolean legacy){var s=users.findAuthorizationSnapshot(user.getId()).orElseThrow();return (legacy?tokens.issueLegacyWithoutAuthTime(s,role,LoginType.EMAIL_CODE):tokens.issue(s,role,LoginType.EMAIL_CODE,now.get().minusSeconds(600))).tokenValue();}
    @Test void should_project_only_five_security_fields_without_writing_user(){
        var user=save(UserRole.ADMIN,"alice@example.com","test-password");var before=jdbc.queryForMap("select * from users where id=?",user.getId());var row=reader.load(user.getId());
        assertEquals(5,UserReauthenticationSnapshot.class.getRecordComponents().length);assertEquals(user.getPasswordHash(),row.passwordHash());assertEquals(user.getEmail(),row.email());assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));
    }
}
