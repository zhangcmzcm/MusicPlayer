package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.*;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class PasswordResetVerificationIntegrationSupport {
 static final String PATH="/api/v1/users/password-reset-requests",P="music:auth:password-reset:";
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
 @DynamicPropertySource static void deps(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));r.add("spring.data.redis.username",()->"default");r.add("spring.data.redis.password",()->"");r.add("spring.data.redis.database",()->"0");}
 @Autowired MockMvc mvc;@Autowired StringRedisTemplate redis;@Autowired JdbcTemplate jdbc;@Autowired PasswordResetTaskProcessor processor;@MockitoSpyBean PasswordHasher hasher;@MockitoSpyBean JwtTokenService tokens;
 @MockitoSpyBean PasswordResetRequestStore store;@MockitoSpyBean PasswordResetSnapshotReader reader;@MockitoSpyBean UserRepository users;
 @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
 @MockitoSpyBean PasswordResetConfirmationStore confirmations;
 @MockitoSpyBean PasswordResetConfirmationTransactionService updater;
 @MockitoSpyBean PasswordResetConfirmationSnapshotReader confirmationReader;
 @MockitoSpyBean PasswordResetTicketStore tickets;
 @MockitoSpyBean org.springframework.transaction.PlatformTransactionManager transactions;
 @MockitoBean PasswordResetEmailSender mail;@MockitoBean PasswordResetWorkerLifecycle lifecycle;
 final List<PasswordResetMessage> sent=new CopyOnWriteArrayList<>();final JsonMapper json=JsonMapper.builder().build();
 @BeforeEach void reset(){users.deleteAll();var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);sent.clear();doAnswer(i->{sent.add(i.getArgument(0));return null;}).when(mail).send(any());}
 UserEntity save(String email,UserRole role){return users.saveAndFlush(new UserEntity("u"+UUID.randomUUID().toString().replace("-","").substring(0,16),email,hasher.hash("test-password"),role));}
 record Accepted(String id,Instant expiresAt){String digest(){return PasswordResetIdentifiers.digest(id);}}
 MvcResult request(String email)throws Exception{return mvc.perform(post(PATH).contentType("application/json").content(json.writeValueAsString(Map.of("email",email)))).andReturn();}
 Accepted accept(String email)throws Exception{var response=request(email);assertEquals(202,response.getResponse().getStatus(),response.getResponse().getContentAsString());var tree=json.readTree(response.getResponse().getContentAsString());assertEquals(2,tree.size());assertEquals("no-store",response.getResponse().getHeader("Cache-Control"));assertNull(response.getResponse().getHeader("Location"));return new Accepted(tree.get("challengeId").asString(),Instant.parse(tree.get("expiresAt").asString()));}
 String digest(String email){return PasswordResetIdentifiers.digest(email);}
 String active(String email){return redis.opsForValue().get(P+"active:"+digest(email));}
 Map<Object,Object> activeState(Accepted a){return redis.opsForHash().entries(P+"challenge:"+a.digest());}
 Map<String,Object> row(UserEntity user){return jdbc.queryForMap("select * from users where id=?",user.getId());}
 void cooldown(String email){redis.delete(P+"email-cooldown:"+digest(email));}
 String token(UserEntity user){return tokens.issue(users.findAuthorizationSnapshot(user.getId()).orElseThrow(),user.getUserRole(),LoginType.USERNAME_PASSWORD,Instant.now().minusSeconds(600)).tokenValue();}
 static final String CONFIRM="/api/v1/users/password-resets";
 record Ready(Accepted accepted,String code) {}
 Ready ready(String email) throws Exception {
     var accepted=accept(email); assertTrue(processor.processNext());
     assertEquals(accepted.digest(),active(email));
     return new Ready(accepted,sent.getLast().code());
 }
 MvcResult confirm(Ready ready,String password) throws Exception {
     return mvc.perform(post(CONFIRM).contentType("application/json").content(json.writeValueAsString(Map.of("challengeId",ready.accepted().id(),"code",ready.code(),"newPassword",password)))).andReturn();
 }
 void assertProblem(MvcResult result,int status,String code) throws Exception {
     assertEquals(status,result.getResponse().getStatus(),result.getResponse().getContentAsString());
     assertEquals(code,json.readTree(result.getResponse().getContentAsString()).get("code").asString());
     assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));
 }

 static final String VERIFY="/api/v1/users/password-reset-verifications",T=PasswordResetTicketStore.PREFIX;
 MvcResult verifyCode(Ready ready) throws Exception {
     return mvc.perform(post(VERIFY).contentType("application/json").content(json.writeValueAsString(Map.of("challengeId",ready.accepted().id(),"code",ready.code())))).andReturn();
 }
 String issuedTicket(MvcResult result) throws Exception {
     assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());
     var body=json.readTree(result.getResponse().getContentAsString()); assertEquals(2,body.size());
     assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));assertNull(result.getResponse().getHeader("Location"));
     return body.get("resetTicket").asString();
 }
 long expiry(String key) { return redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key)); }
 void clearIpWindow() { redis.delete(PasswordResetConfirmationStore.PREFIX+"ip:"+digest("127.0.0.1")); }
 int access(String token) throws Exception { return mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus(); }

}
