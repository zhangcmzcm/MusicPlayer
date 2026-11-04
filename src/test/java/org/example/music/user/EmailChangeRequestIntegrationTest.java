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
class EmailChangeRequestIntegrationTest {
    static final String PATH="/api/v1/users/me/email-change-requests";
    static final String PREFIX="music:auth:email-change:";
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse("mysql:8.4")).withDatabaseName("music");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordHasher hasher;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean JwtTokenService tokens;
    @MockitoSpyBean EmailChangeRequestStore store;
    @MockitoBean EmailChangeEmailSender mail;
    @MockitoSpyBean BoundedEmailChangeEmailDelivery delivery;
    @MockitoBean(name="authenticationClock") java.time.Clock clock;
    final AtomicReference<Instant> now=new AtomicReference<>();
    final AtomicReference<EmailChangeMessage> sent=new AtomicReference<>();
    final JsonMapper json=JsonMapper.builder().build();
    @DynamicPropertySource static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);registry.add("spring.datasource.username",MYSQL::getUsername);registry.add("spring.datasource.password",MYSQL::getPassword);
        registry.add("spring.data.redis.host",REDIS::getHost);registry.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.username",()->"default");registry.add("spring.data.redis.password",()->"");registry.add("spring.data.redis.database",()->"0");
    }
    @BeforeEach void resetData() {
        now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));when(clock.instant()).thenAnswer(i->now.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        users.deleteAll();var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);sent.set(null);
        doAnswer(i->{sent.set(i.getArgument(0));return null;}).when(mail).send(any());
    }
    UserEntity save(UserRole role,String email) {return users.saveAndFlush(new UserEntity("user"+UUID.randomUUID().toString().replace("-", ""),email,hasher.hash("test-password"),role));}
    String token(UserEntity user,UserRole loginRole,boolean legacy) {
        var snapshot=users.findAuthorizationSnapshot(user.getId()).orElseThrow();
        var result=legacy?tokens.issueLegacyWithoutAuthTime(snapshot,loginRole,LoginType.USERNAME_PASSWORD):tokens.issue(snapshot,loginRole,LoginType.USERNAME_PASSWORD,now.get().minusSeconds(299));
        clearInvocations(tokens);return result.tokenValue();
    }
    MvcResult send(String token) throws Exception {return mvc.perform(post(PATH).header("Authorization","Bearer "+token).contentType("application/json").content("{\"newEmail\":\"new@example.com\"}")).andReturn();}
    String key(String kind,UserEntity user){return PREFIX+(kind.equals("cooldown")?"user-cooldown":kind.equals("daily")?"user-daily":kind)+":"+user.getId();}
    long expiry(String key){return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key));}
    Map<Object,Object> active(UserEntity user){return redis.opsForHash().entries(key("active",user));}
    void clearCooldown(UserEntity user){redis.expire(key("cooldown",user),Duration.ZERO);var keys=redis.keys(PREFIX+"target-cooldown:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    void assertCode(MvcResult response,int status,String code) throws Exception {
        assertEquals(status,response.getResponse().getStatus(),response.getResponse().getContentAsString());assertEquals(code,json.readTree(response.getResponse().getContentAsString()).get("code").asString());
    }
    @ParameterizedTest @CsvSource({"USER,USER,false","ADMIN,ADMIN,false","ADMIN,USER,false"})
    void should_use_real_filter_chain_current_email_and_leave_entire_account_unchanged(UserRole account,UserRole login,boolean legacy) throws Exception {
        var user=save(account,"Alice@Example.com");String token=token(user,login,legacy);var before=jdbc.queryForMap("select * from users where id=?",user.getId());
        String remoteIp="203.0.113.8",forgedIp="198.51.100.77";
        var result=mvc.perform(post(PATH).header("Authorization","Bearer "+token).header("X-User-Id","999").header("X-Email","victim@example.com").header("X-Forwarded-For",forgedIp).param("email","victim@example.com").param("userId","999").contentType("application/json").content("{\"newEmail\":\" New@Example.com \"}").with(request->{request.setRemoteAddr(remoteIp);return request;}))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location")).andReturn();
        var body=json.readTree(result.getResponse().getContentAsString());assertEquals(2,body.size());String id=body.get("challengeId").asString();assertEquals(32,Base64.getUrlDecoder().decode(id).length);
        var state=active(user);assertEquals(10,state.size());assertEquals("ACTIVE",state.get("state"));assertEquals("0",state.get("attempts"));assertEquals("new@example.com",sent.get().recipient());assertTrue(sent.get().code().matches("[0-9]{6}"));
        assertTrue(hasher.matches(sent.get().code(),(String)state.get("codeHash")));assertFalse(state.containsValue(id));assertFalse(state.containsValue(sent.get().code()));
        String digest=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));assertEquals(digest,state.get("challengeDigest"));
        assertEquals(300000,Long.parseLong((String)state.get("expiresAtMs"))-Long.parseLong((String)state.get("reservedAtMs")));
        assertEquals(Instant.parse(body.get("expiresAt").asString()).toEpochMilli(),expiry(key("active",user)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));
        String remoteKey=PREFIX+"ip:"+Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(remoteIp.getBytes(StandardCharsets.UTF_8)));
        String forgedKey=PREFIX+"ip:"+Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(forgedIp.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Set.of(remoteKey),redis.keys(PREFIX+"ip:*"));
        assertEquals("1",redis.opsForHash().get(remoteKey,"count"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(forgedKey)));verifyNoInteractions(tokens);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void should_allow_first_binding_without_changing_account(String email) throws Exception {
        var user=save(UserRole.USER,email);var before=jdbc.queryForMap("select * from users where id=?",user.getId());
        assertEquals(201,send(token(user,UserRole.USER,false)).getResponse().getStatus());
        assertEquals("",active(user).get("currentEmail"));assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));
    }
    @Test void should_reject_bad_stored_email_before_admission() throws Exception {
        var user=save(UserRole.USER,"broken");assertCode(send(token(user,UserRole.USER,false)),503,"EMAIL_CHANGE_REQUEST_UNAVAILABLE");assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @Test void should_reject_forged_body_and_missing_authentication() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization","Bearer "+token).contentType("application/json").content("{\"email\":\"victim@example.com\"}"))
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
        doThrow(new EmailChangeSendFailedException(new RuntimeException("secret"))).when(mail).send(any());
        assertCode(send(token),502,"EMAIL_SEND_FAILED");assertEquals(original,active(user));assertEquals(expires,expiry(key("active",user)));assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));
        assertEquals("2",redis.opsForHash().get(key("daily",user),"count"));assertCode(send(token),429,"EMAIL_CHANGE_RATE_LIMITED");
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
                case "database"->doThrow(new DataAccessResourceFailureException("secret")).when(users).findEmailChangeSnapshot(user.getId());
            }return null;
        }).when(mail).send(any());
        var result=send(token);boolean conflict=change.equals("email")||change.equals("unbound");
        assertCode(result,change.equals("database")?503:conflict?409:401,change.equals("database")?"EMAIL_CHANGE_REQUEST_UNAVAILABLE":conflict?"EMAIL_CHANGE_REQUEST_CONFLICT":"TOKEN_REVOKED");
        assertTrue(active(user).isEmpty());assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
    }
    @Test void should_not_allow_late_old_send_to_activate_or_cancel_new_request() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        doAnswer(i->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return null;}).doNothing().when(mail).send(any());
        try {
            var old=executor.submit(()->send(token));assertTrue(entered.await(5,TimeUnit.SECONDS));redis.expire(key("pending",user),Duration.ZERO);clearCooldown(user);
            var next=send(token);assertEquals(201,next.getResponse().getStatus());var current=active(user);long deadline=expiry(key("active",user));
            release.countDown();assertCode(old.get(10,TimeUnit.SECONDS),409,"EMAIL_CHANGE_REQUEST_CONFLICT");assertEquals(current,active(user));assertEquals(deadline,expiry(key("active",user)));
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"reserve","activate"})
    void should_fail_closed_when_actual_redis_operation_succeeds_but_confirmation_is_lost(String operation) throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        if(operation.equals("reserve"))doAnswer(i->{i.callRealMethod();throw new EmailChangeRequestUnavailableException(new RuntimeException("confirmation lost"));}).when(store).reserve(any(),anyString(),anyString());
        else doAnswer(i->{i.callRealMethod();throw new EmailChangeRequestUnavailableException(new RuntimeException("confirmation lost"));}).when(store).activate(any(),any());
        assertCode(send(token),503,"EMAIL_CHANGE_REQUEST_UNAVAILABLE");assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));
        if(operation.equals("activate")){assertEquals("ACTIVE",active(user).get("state"));verify(mail).send(any());}else{assertTrue(active(user).isEmpty());verifyNoInteractions(mail);}
    }
    @Test void should_fail_closed_on_real_redis_corruption_without_sending() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);redis.opsForValue().set(key("daily",user),"wrong-type");
        assertCode(send(token),503,"EMAIL_CHANGE_REQUEST_UNAVAILABLE");assertEquals("wrong-type",redis.opsForValue().get(key("daily",user)));verifyNoInteractions(mail);
    }
    @ParameterizedTest @ValueSource(strings={"timeout","capacity"})
    void should_not_activate_when_delivery_wait_times_out_or_executor_is_full(String failure) throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        if(failure.equals("timeout"))doThrow(new EmailChangeSendFailedException(new TimeoutException())).when(delivery).send(any());
        else doThrow(new EmailChangeRequestUnavailableException(new RejectedExecutionException())).when(delivery).send(any());
        assertCode(send(token),failure.equals("timeout")?502:503,failure.equals("timeout")?"EMAIL_SEND_FAILED":"EMAIL_CHANGE_REQUEST_UNAVAILABLE");
        assertTrue(active(user).isEmpty());assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));verifyNoInteractions(mail);
    }
    @Test void should_keep_primary_send_failure_if_real_pending_cleanup_cannot_confirm() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);
        doThrow(new EmailChangeSendFailedException(new RuntimeException("secret"))).when(mail).send(any());
        doThrow(new EmailChangeRequestUnavailableException(new RuntimeException("secret"))).when(store).cancel(any(),anyString());
        assertCode(send(token),502,"EMAIL_SEND_FAILED");assertTrue(active(user).isEmpty());assertEquals("PREPARED",redis.opsForHash().get(key("pending",user),"state"));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
    }
    @Test void should_admit_only_one_real_http_request_in_same_user_race() throws Exception {
        var user=save(UserRole.USER,"a@example.com");String token=token(user,UserRole.USER,false);var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(6);List<Future<Integer>> results=new ArrayList<>();
        try {
            for(int i=0;i<6;i++)results.add(executor.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return send(token).getResponse().getStatus();}));start.countDown();
            int successes=0;for(var result:results){int status=result.get(10,TimeUnit.SECONDS);assertTrue(status==201||status==429);if(status==201)successes++;}assertEquals(1,successes);verify(mail,times(1)).send(any());assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
        }finally{start.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }

    MvcResult sendTo(String token,String email,String ip) throws Exception {
        return mvc.perform(post(PATH).header("Authorization","Bearer "+token).contentType("application/json")
                .content(json.writeValueAsString(Map.of("newEmail",email))).with(r->{r.setRemoteAddr(ip);return r;})).andReturn();
    }
    @ParameterizedTest @ValueSource(ints={299,300,301})
    void should_use_real_auth_time_inclusive_boundary_not_iat(int age) throws Exception {
        var user=save(UserRole.USER,"old@example.com");var snapshot=users.findAuthorizationSnapshot(user.getId()).orElseThrow();
        String value=tokens.issue(snapshot,UserRole.USER,LoginType.USERNAME_PASSWORD,now.get().minusSeconds(age)).tokenValue();clearInvocations(tokens);
        var response=send(value);assertEquals(age<=300?201:401,response.getResponse().getStatus(),response.getResponse().getContentAsString());
        if(age>300){assertCode(response,401,"REAUTHENTICATION_REQUIRED");assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);}
    }
    @Test void should_reject_legacy_token_before_media_type_or_body_but_keep_ordinary_login() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String legacy=token(user,UserRole.USER,true);
        mvc.perform(post(PATH).header("Authorization","Bearer "+legacy).contentType("text/plain").content("invalid"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").header("Authorization","Bearer "+legacy)).andExpect(status().isOk());
        assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @ParameterizedTest @CsvSource({"self,409,EMAIL_UNCHANGED","other,409,EMAIL_ALREADY_EXISTS","accent-self,409,EMAIL_UNCHANGED","accent-other,409,EMAIL_ALREADY_EXISTS"})
    void should_follow_real_mysql_email_uniqueness(String scenario,int expected,String code) throws Exception {
        var user=save(UserRole.USER,scenario.equals("self")?"New@Example.com":scenario.equals("accent-self")?"résumé@example.com":"old@example.com");
        if(scenario.equals("other"))save(UserRole.USER,"new@example.com");
        if(scenario.equals("accent-other"))save(UserRole.USER,"résumé@example.com");
        String target=scenario.startsWith("accent")?"resume@example.com":"new@example.com";
        assertCode(sendTo(token(user,UserRole.USER,false),target,"198.51.100.1"),expected,code);
        assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @Test void should_recheck_target_occupancy_after_mail_without_changing_applicant() throws Exception {
        var user=save(UserRole.USER,"old@example.com");var other=save(UserRole.USER,null);String value=token(user,UserRole.USER,false);
        var before=jdbc.queryForMap("select * from users where id=?",user.getId());
        doAnswer(i->{jdbc.update("update users set email=? where id=?","new@example.com",other.getId());return null;}).when(mail).send(any());
        assertCode(send(value),409,"EMAIL_ALREADY_EXISTS");assertTrue(active(user).isEmpty());assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",user))));
        assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));assertEquals("1",redis.opsForHash().get(key("daily",user),"count"));
    }
    @Test void should_not_reset_target_or_user_cooldown_by_switching_accounts_or_targets() throws Exception {
        var first=save(UserRole.USER,null);var second=save(UserRole.USER,null);String a=token(first,UserRole.USER,false),b=token(second,UserRole.USER,false);
        assertEquals(201,sendTo(a,"target@example.com","198.51.100.1").getResponse().getStatus());
        assertCode(sendTo(b," TARGET@example.com ","198.51.100.2"),429,"EMAIL_CHANGE_RATE_LIMITED");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("daily",second))));
        assertCode(sendTo(a,"different@example.com","198.51.100.3"),429,"EMAIL_CHANGE_RATE_LIMITED");
        assertEquals("1",redis.opsForHash().get(key("daily",first),"count"));
    }
    @Test void should_limit_shared_ip_across_different_accounts_and_targets() throws Exception {
        for(int i=0;i<6;i++){
            var user=save(UserRole.USER,null);var response=sendTo(token(user,UserRole.USER,false),"target"+i+"@example.com","198.51.100.1");
            if(i<5)assertEquals(201,response.getResponse().getStatus());else{assertCode(response,429,"EMAIL_CHANGE_RATE_LIMITED");assertFalse(Boolean.TRUE.equals(redis.hasKey(key("daily",user))));}
        }
        verify(mail,times(5)).send(any());
    }
    @Test void should_preserve_request_and_old_auth_time_across_real_password_reauthentication() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String old=token(user,UserRole.USER,false);assertEquals(201,send(old).getResponse().getStatus());var original=active(user);
        now.set(now.get().plusSeconds(2));assertCode(send(old),401,"REAUTHENTICATION_REQUIRED");
        var verified=mvc.perform(post("/api/v1/users/me/reauthentications").header("Authorization","Bearer "+old).contentType("application/json")
                .content("{\"method\":\"PASSWORD\",\"password\":\"test-password\"}")).andExpect(status().isOk()).andReturn();
        String fresh=json.readTree(verified.getResponse().getContentAsString()).get("accessToken").asString();
        assertEquals(original,active(user));assertCode(send(old),401,"REAUTHENTICATION_REQUIRED");clearCooldown(user);
        assertEquals(201,sendTo(fresh,"other@example.com","198.51.100.2").getResponse().getStatus());
        for(String valid:List.of(old,fresh))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/users/me").header("Authorization","Bearer "+valid)).andExpect(status().isOk());
        assertEquals(0L,((Number)jdbc.queryForMap("select token_version from users where id=?",user.getId()).get("token_version")).longValue());
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/json;charset=UTF-16","bad"})
    void should_return_real_415_and_keep_legacy_reader_400(String type) throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,false);
        mvc.perform(post(PATH).header("Authorization","Bearer "+value).contentType(type).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE")).andExpect(jsonPath("$.type").value("urn:problem:music:unsupported_media_type"));
        mvc.perform(post("/api/v1/users/me/reauthentications").header("Authorization","Bearer "+value).contentType(type).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertTrue(redis.keys(PREFIX+"*").isEmpty());verifyNoInteractions(mail);
    }
    @Test void should_generate_openapi_for_exact_request_response_and_error_contract() throws Exception {
        var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,false);
        var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs").header("Authorization","Bearer "+value)).andExpect(status().isOk()).andReturn();
        var document=json.readTree(result.getResponse().getContentAsString());var operation=document.get("paths").get(PATH).get("post");
        for(String code:List.of("201","400","401","403","409","415","429","500","502","503"))assertTrue(operation.get("responses").has(code),code);
        assertEquals("#/components/schemas/EmailChangeRequest",operation.get("requestBody").get("content").get("application/json").get("schema").get("$ref").asString());
        assertEquals(120,document.get("components").get("schemas").get("EmailChangeRequest").get("properties").get("newEmail").get("maxLength").asInt());
    }

    @Test void should_reject_isolated_unicode_escape_before_database_query_or_quota() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,false);
        mvc.perform(post(PATH).header("Authorization","Bearer "+value).contentType("application/json")
                .content("{\"newEmail\":\"\\ud800@example.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(users,never()).findEmailChangeSnapshot(anyInt());verifyNoInteractions(mail);
        assertTrue(redis.keys(PREFIX+"*").isEmpty());
    }
}
