package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.LoginType;
import org.example.music.user.email.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EnabledIfDockerAvailable
class ReauthenticationEmailRequestIntegrationTest {
    static final String PATH="/api/v1/users/me/reauthentication-email-requests";
    static final String PREFIX="music:auth:reauth-email:";
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean JwtTokenService tokens;
    @MockitoSpyBean ReauthenticationEmailRequestStore store;
    @MockitoBean ReauthenticationEmailSender mail;
    @MockitoSpyBean BoundedReauthenticationEmailDelivery delivery;
    final AtomicReference<ReauthenticationEmailMessage> sent=new AtomicReference<>();
    final JsonMapper json=JsonMapper.builder().build();
    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);registry.add("spring.datasource.username",MYSQL::getUsername);registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username",()->"default");registry.add("spring.data.redis.password",()->"");registry.add("spring.data.redis.database",()->"0");
    }
    @BeforeEach void resetData() {
        users.deleteAll();var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);sent.set(null);
        doAnswer(i->{sent.set(i.getArgument(0));return null;}).when(mail).send(any());
    }
    UserEntity save(UserRole role,String email) {return users.saveAndFlush(new UserEntity("user"+UUID.randomUUID().toString().replace("-", ""),email,hasher.hash("test-password"),role));}
    String token(UserEntity user,UserRole loginRole,boolean legacy) {
        var snapshot=users.findAuthorizationSnapshot(user.getId()).orElseThrow();
        var result=legacy?tokens.issueLegacyWithoutAuthTime(snapshot,loginRole,LoginType.USERNAME_PASSWORD):tokens.issue(snapshot,loginRole,LoginType.USERNAME_PASSWORD,Instant.now().minusSeconds(900));
        clearInvocations(tokens);return result.tokenValue();
    }
    MvcResult send(String token) throws Exception {return mvc.perform(post(PATH).header("Authorization","Bearer "+token)).andReturn();}
    String key(String kind,UserEntity user){return PREFIX+kind+":"+user.getId();}
    long expiry(String key){return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key));}
    Map<Object,Object> active(UserEntity user){return redis.opsForHash().entries(key("active",user));}
    void clearCooldown(UserEntity user){redis.expire(key("cooldown",user),Duration.ZERO);}
    void assertCode(MvcResult response,int status,String code) throws Exception {
        assertEquals(status,response.getResponse().getStatus(),response.getResponse().getContentAsString());assertEquals(code,json.readTree(response.getResponse().getContentAsString()).get("code").asString());
    }
    @ParameterizedTest @CsvSource({"USER,USER,false","USER,USER,true","ADMIN,ADMIN,false","ADMIN,USER,true"})
    void should_use_real_filter_chain_current_email_and_leave_entire_account_unchanged(UserRole account,UserRole login,boolean legacy) throws Exception {
        var user=save(account,"Alice@Example.com");String token=token(user,login,legacy);var before=jdbc.queryForMap("select * from users where id=?",user.getId());
        var result=mvc.perform(post(PATH).header("Authorization","Bearer "+token).header("X-User-Id","999").header("X-Email","victim@example.com").header("X-Forwarded-For","198.51.100.77").param("email","victim@example.com").param("userId","999"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location")).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString());assertEquals(2,body.size());String id=body.get("challengeId").asString();assertEquals(32,Base64.getUrlDecoder().decode(id).length);
        var state=active(user);assertEquals(9,state.size());assertEquals("ACTIVE",state.get("state"));assertEquals("0",state.get("attempts"));assertEquals("alice@example.com",sent.get().recipient());assertTrue(sent.get().code().matches("[0-9]{6}"));
        assertTrue(hasher.matches(sent.get().code(),(String)state.get("codeHash")));assertFalse(state.containsValue(id));assertFalse(state.containsValue(sent.get().code()));
        String digest=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));assertEquals(digest,state.get("challengeDigest"));
        assertEquals(300000,Long.parseLong((String)state.get("expiresAtMs"))-Long.parseLong((String)state.get("reservedAtMs")));
        assertEquals(Instant.parse(body.get("expiresAt").asString()).toEpochMilli(),expiry(key("active",user)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));
        assertFalse(redis.keys(PREFIX+"ip:*").toString().contains("127.0.0.1"));verifyNoInteractions(tokens);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void should_reject_missing_email_without_spending_quota(String email) throws Exception {
        var user=save(UserRole.USER,email);assertCode(send(token(user,UserRole.USER,false)),409,"CURRENT_EMAIL_NOT_BOUND");assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @Test void should_reject_bad_stored_email_before_admission() throws Exception {
        var user=save(UserRole.USER,"broken");assertCode(send(token(user,UserRole.USER,false)),503,"REAUTH_EMAIL_REQUEST_UNAVAILABLE");assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @Test void should_reject_forged_body_and_missing_authentication() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization","Bearer "+token).content("{\"email\":\"victim@example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @Test void should_reject_bad_signature_and_revoked_token_before_sending() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);String[] parts=token.split("\\.");
        String bad=parts[0]+"."+parts[1]+"."+(parts[2].charAt(0)=='A'?"B":"A")+parts[2].substring(1);
        assertEquals(401,send(bad).getResponse().getStatus());jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());
        assertCode(send(token),401,"TOKEN_REVOKED");assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @ParameterizedTest @ValueSource(strings={"expired","subject","version","role","issuer","audience"})
    void should_reject_invalid_real_signed_token_claims_without_touching_mail_state(String damage) throws Exception {
        var user=save(UserRole.USER,"a@example.com");long now=Instant.now().getEpochSecond();
        Map<String,Object> claims=new HashMap<>(Map.of("sub",user.getId().toString(),"iss","music-api","aud",List.of("music-client"),"iat",now-10,"exp",now+600,"role","USER","tokenVersion",0));
        switch(damage){
            case "expired"->{claims.put("iat",now-240);claims.put("exp",now-120);}
            case "subject"->claims.put("sub","2147483648");
            case "version"->claims.remove("tokenVersion");
            case "role"->claims.put("role","OTHER");
            case "issuer"->claims.put("iss","other-api");
            case "audience"->claims.put("aud",List.of("other-client"));
        }
        var jwt=new JWSObject(new JWSHeader(JWSAlgorithm.HS256),new Payload(claims));jwt.sign(new MACSigner("test-secret-that-is-long-enough-1234567890".getBytes(StandardCharsets.UTF_8)));
        assertEquals(401,send(jwt.serialize()).getResponse().getStatus());verifyNoInteractions(mail);assertTrue(redis.keys(PREFIX+"*").isEmpty());
    }
    @Test void should_preserve_old_active_and_original_expiry_on_send_failure_without_refund() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);assertEquals(201,send(token).getResponse().getStatus());
        var original=active(user);long expires=expiry(key("active",user));clearCooldown(user);
        doThrow(new ReauthenticationEmailSendFailedException(new RuntimeException("secret"))).when(mail).send(any());
        assertCode(send(token),502,"EMAIL_SEND_FAILED");assertEquals(original,active(user));assertEquals(expires,expiry(key("active",user)));assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));
        assertEquals("2",redis.opsForHash().get(key("daily",user),"count"));assertCode(send(token),429,"REAUTH_EMAIL_RATE_LIMITED");
    }
    @Test void should_keep_old_active_while_new_request_is_prepared_and_replace_after_send_confirmation() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);assertEquals(201,send(token).getResponse().getStatus());var old=active(user);clearCooldown(user);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        doAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(mail).send(any());
        try {
            var result=executor.submit(()->send(token));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(old,active(user));
            var pending=redis.opsForHash().entries(key("pending",user));assertEquals("PREPARED",pending.get("state"));long deadline=Long.parseLong((String)pending.get("expiresAtMs"));
            release.countDown();assertEquals(201,result.get(10,TimeUnit.SECONDS).getResponse().getStatus());assertEquals(pending.get("challengeDigest"),active(user).get("challengeDigest"));assertEquals(deadline,expiry(key("active",user)));
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"email","unbound","version","role","delete","database"})
    void should_recheck_real_database_after_mail_and_never_activate_stale_binding(String change) throws Exception {
        var user=save(UserRole.ADMIN,"a@example.com");String token=token(user,UserRole.ADMIN,false);
        doAnswer(i->{
            switch(change){
                case "email"->jdbc.update("update users set email='b@example.com' where id=?",user.getId());
                case "unbound"->jdbc.update("update users set email=null where id=?",user.getId());
                case "version"->jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());
                case "role"->jdbc.update("update users set user_role='USER' where id=?",user.getId());
                case "delete"->jdbc.update("delete from users where id=?",user.getId());
                case "database"->doThrow(new DataAccessResourceFailureException("secret")).when(users).findAuthorizationSnapshot(user.getId());
            }return null;
        }).when(mail).send(any());
        var result=send(token);boolean conflict=change.equals("email")||change.equals("unbound");
        assertCode(result,change.equals("database")?503:conflict?409:401,change.equals("database")?"REAUTH_EMAIL_REQUEST_UNAVAILABLE":conflict?"REAUTH_EMAIL_REQUEST_CONFLICT":"TOKEN_REVOKED");
        assertTrue(active(user).isEmpty());assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
    }
    @Test void should_not_allow_late_old_send_to_activate_or_cancel_new_request() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        doAnswer(i->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return null;}).doNothing().when(mail).send(any());
        try {
            var old=executor.submit(()->send(token));assertTrue(entered.await(5,TimeUnit.SECONDS));redis.expire(key("pending",user),Duration.ZERO);clearCooldown(user);
            var next=send(token);assertEquals(201,next.getResponse().getStatus());var current=active(user);long deadline=expiry(key("active",user));
            release.countDown();assertCode(old.get(10,TimeUnit.SECONDS),409,"REAUTH_EMAIL_REQUEST_CONFLICT");assertEquals(current,active(user));assertEquals(deadline,expiry(key("active",user)));
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"reserve","activate"})
    void should_fail_closed_when_actual_redis_operation_succeeds_but_confirmation_is_lost(String operation) throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        if(operation.equals("reserve"))doAnswer(i->{i.callRealMethod();throw new ReauthenticationEmailRequestUnavailableException(new RuntimeException("confirmation lost"));}).when(store).reserve(any(),anyString(),anyString());
        else doAnswer(i->{i.callRealMethod();throw new ReauthenticationEmailRequestUnavailableException(new RuntimeException("confirmation lost"));}).when(store).activate(any(),any());
        assertCode(send(token),503,"REAUTH_EMAIL_REQUEST_UNAVAILABLE");assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));
        if(operation.equals("activate")){assertEquals("ACTIVE",active(user).get("state"));verify(mail).send(any());}else{assertTrue(active(user).isEmpty());verifyNoInteractions(mail);}
    }
    @Test void should_fail_closed_on_real_redis_corruption_without_sending() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);redis.opsForValue().set(key("daily",user),"wrong-type");
        assertCode(send(token),503,"REAUTH_EMAIL_REQUEST_UNAVAILABLE");assertEquals("wrong-type",redis.opsForValue().get(key("daily",user)));verifyNoInteractions(mail);
    }
    @ParameterizedTest @ValueSource(strings={"timeout","capacity"})
    void should_not_activate_when_delivery_wait_times_out_or_executor_is_full(String failure) throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        if(failure.equals("timeout"))doThrow(new ReauthenticationEmailSendFailedException(new TimeoutException())).when(delivery).send(any());
        else doThrow(new ReauthenticationEmailRequestUnavailableException(new RejectedExecutionException())).when(delivery).send(any());
        assertCode(send(token),failure.equals("timeout")?502:503,failure.equals("timeout")?"EMAIL_SEND_FAILED":"REAUTH_EMAIL_REQUEST_UNAVAILABLE");
        assertTrue(active(user).isEmpty());assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));verifyNoInteractions(mail);
    }
    @Test void should_keep_primary_send_failure_if_real_pending_cleanup_cannot_confirm() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        doThrow(new ReauthenticationEmailSendFailedException(new RuntimeException("secret"))).when(mail).send(any());
        doThrow(new ReauthenticationEmailRequestUnavailableException(new RuntimeException("secret"))).when(store).cancel(any(),anyString());
        assertCode(send(token),502,"EMAIL_SEND_FAILED");assertTrue(active(user).isEmpty());assertEquals("PREPARED",redis.opsForHash().get(key("pending",user),"state"));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
    }
    @Test void should_admit_only_one_real_http_request_in_same_user_race() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(6);List<Future<Integer>> results=new ArrayList<>();
        try {
            for(int i=0;i<6;i++)results.add(executor.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return send(token).getResponse().getStatus();}));start.countDown();
            int successes=0;for(var result:results){int status=result.get(10,TimeUnit.SECONDS);assertTrue(status==201||status==429);if(status==201)successes++;}assertEquals(1,successes);verify(mail,times(1)).send(any());assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
        }finally{start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
