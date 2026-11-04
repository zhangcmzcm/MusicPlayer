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
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.user.domain.*;
import org.example.music.user.dto.LoginType;
import org.example.music.user.email.*;
import org.example.music.user.repository.*;
import org.example.music.user.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class EmailChangeConfirmationIntegrationSupport {
    static final String PATH="/api/v1/users/me/email", PREFIX="music:auth:email-change:";
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailChangeConfirmationSnapshotReader reader;
    @Autowired EmailChangeConfirmationTransactionService updater;
    @Autowired EmailChangeConfirmationRules rules;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean PasswordHasher hasher;
    @MockitoSpyBean JwtTokenService tokens;
    @MockitoSpyBean EmailChangeConfirmationStore confirmations;
    @MockitoSpyBean SecureVerificationCodeGenerator codes;
    @MockitoSpyBean PlatformTransactionManager transactions;
    @MockitoBean EmailChangeEmailSender mail;
    @MockitoBean(name="authenticationClock") Clock clock;
    final AtomicReference<Instant> now=new AtomicReference<>();
    final AtomicReference<EmailChangeMessage> sent=new AtomicReference<>();
    final JsonMapper json=JsonMapper.builder().build();

    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);registry.add("spring.datasource.username",MYSQL::getUsername);registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));registry.add("spring.data.redis.username",()->"default");registry.add("spring.data.redis.password",()->"");registry.add("spring.data.redis.database",()->"0");
    }
    @BeforeEach void setupData(){
        now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));when(clock.instant()).thenAnswer(i->now.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        users.deleteAll();var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);sent.set(null);
        doAnswer(i->{sent.set(i.getArgument(0));return null;}).when(mail).send(any());
    }
    UserEntity save(UserRole role,String email){return users.saveAndFlush(new UserEntity("u"+UUID.randomUUID().toString().replace("-","").substring(0,16),email,hasher.hash("test-password"),role));}
    String token(UserEntity user,UserRole role,Long age){var s=users.findAuthorizationSnapshot(user.getId()).orElseThrow();String value=(age==null?tokens.issueLegacyWithoutAuthTime(s,role,LoginType.USERNAME_PASSWORD):tokens.issue(s,role,LoginType.USERNAME_PASSWORD,now.get().minusSeconds(age))).tokenValue();clearInvocations(tokens);return value;}
    record Challenge(String id,String code,String email){@Override public String toString(){return "Challenge[redacted]";}}
    Challenge send(String token,String email) throws Exception {
        var result=mvc.perform(post("/api/v1/users/me/email-change-requests").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(Map.of("newEmail",email))))
                .andExpect(status().isCreated()).andReturn();
        return new Challenge(json.readTree(result.getResponse().getContentAsString()).get("challengeId").asString(),sent.get().code(),email.trim().toLowerCase(Locale.ROOT));
    }
    MvcResult confirm(String token,Challenge challenge,String code) throws Exception {return mvc.perform(put(PATH).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(Map.of("challengeId",challenge.id(),"code",code)))).andReturn();}
    MvcResult confirm(String token,Challenge challenge) throws Exception {return confirm(token,challenge,challenge.code());}
    void assertCode(MvcResult response,int status,String code) throws Exception {assertEquals(status,response.getResponse().getStatus(),response.getResponse().getContentAsString());assertEquals(code,json.readTree(response.getResponse().getContentAsString()).get("code").asString());}
    String activeKey(UserEntity user){return PREFIX+"active:"+user.getId();}
    String quotaKey(UserEntity user){return "music:auth:email-change-confirmation:user:"+user.getId();}
    Map<String,Object> row(UserEntity user){return jdbc.queryForMap("select * from users where id=?",user.getId());}
    Map<Object,Object> active(UserEntity user){return redis.opsForHash().entries(activeKey(user));}
    void clearSendCooldown(UserEntity user){redis.expire(PREFIX+"user-cooldown:"+user.getId(),Duration.ZERO);var keys=redis.keys(PREFIX+"target-cooldown:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    AuthenticatedUserPrincipal actor(UserEntity user){return new AuthenticatedUserPrincipal(user.getId(),user.getUserRole());}
    AuthenticatedSessionContext session(UserEntity user){return new AuthenticatedSessionContext(((Number)row(user).get("token_version")).longValue(),now.get());}
    void assertOrdinaryAccess(String token,int status) throws Exception {mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andExpect(status().is(status));}
    String wrongCode(Challenge challenge){return challenge.code().equals("000000")?"111111":"000000";}
}
