package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.user.exception.ReauthenticationUnavailableException;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.LoginType;
import org.example.music.user.email.*;
import org.example.music.user.repository.*;
import org.example.music.user.service.ReauthenticationSnapshotReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
    @Autowired WebApplicationContext web;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean PasswordHasher hasher;
    @MockitoSpyBean JwtTokenService tokens;
    @MockitoSpyBean ReauthenticationStore store;
    @MockitoSpyBean SecureVerificationCodeGenerator generator;
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
    String token(UserEntity user,UserRole role,boolean legacy){var s=users.findAuthorizationSnapshot(user.getId()).orElseThrow();var token=(legacy?tokens.issueLegacyWithoutAuthTime(s,role,LoginType.EMAIL_CODE):tokens.issue(s,role,LoginType.EMAIL_CODE,now.get().minusSeconds(600))).tokenValue();clearInvocations(tokens);return token;}
    @AfterEach void should_never_use_legacy_signing_for_reauthentication(){verify(tokens,never()).issueLegacyWithoutAuthTime(any(),any(),any());}
    record Challenge(String id,String code){@Override public String toString(){return "Challenge[redacted]";}}
    Challenge sendChallenge(String token) throws Exception {
        var result=mvc.perform(post(SEND).header("Authorization","Bearer "+token)).andExpect(status().isCreated()).andReturn();
        return new Challenge(json.readTree(result.getResponse().getContentAsString()).get("challengeId").asString(),sent.get().code());
    }
    Map<String,String> passwordBody(String password){return Map.of("method","PASSWORD","password",password);}
    Map<String,String> emailBody(Challenge challenge,String code){return Map.of("method","EMAIL_CODE","challengeId",challenge.id(),"code",code);}
    MvcResult call(String token,Map<String,String> body) throws Exception {return mvc.perform(post(PATH).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body))).andReturn();}
    void assertCode(MvcResult response,int status,String code) throws Exception {assertEquals(status,response.getResponse().getStatus(),response.getResponse().getContentAsString());assertEquals(code,json.readTree(response.getResponse().getContentAsString()).get("code").asString());}
    tools.jackson.databind.JsonNode payload(String token){return json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8));}
    String activeKey(UserEntity user){return "music:auth:reauth-email:active:"+user.getId();}
    String quotaKey(UserEntity user){return "music:auth:reauth:user:"+user.getId();}
    Map<Object,Object> active(UserEntity user){return redis.opsForHash().entries(activeKey(user));}
    void clearCooldown(UserEntity user){redis.expire("music:auth:reauth-email:cooldown:"+user.getId(),Duration.ZERO);}
    String wrongCode(Challenge challenge){return challenge.code().equals("000000")?"111111":"000000";}
    @Test void should_project_only_five_security_fields_without_writing_user(){
        var user=save(UserRole.ADMIN,"alice@example.com","test-password");var before=jdbc.queryForMap("select * from users where id=?",user.getId());var row=reader.load(user.getId());
        assertEquals(5,UserReauthenticationSnapshot.class.getRecordComponents().length);assertEquals(user.getPasswordHash(),row.passwordHash());assertEquals(user.getEmail(),row.email());assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));
    }

    @ParameterizedTest @CsvSource({"USER,USER,false,PASSWORD","USER,USER,true,PASSWORD","ADMIN,USER,false,PASSWORD","ADMIN,ADMIN,false,PASSWORD","USER,USER,false,EMAIL_CODE","USER,USER,true,EMAIL_CODE","ADMIN,USER,false,EMAIL_CODE","ADMIN,ADMIN,false,EMAIL_CODE"})
    void should_verify_self_on_real_chain_and_keep_old_logins_account_role_and_version(UserRole account,UserRole login,boolean legacy,String method) throws Exception {
        var user=save(account,"Alice@Example.com","test-password");String old=token(user,login,legacy),other=token(user,login,false);var before=jdbc.queryForMap("select * from users where id=?",user.getId());
        Map<String,String> body=method.equals("PASSWORD")?passwordBody("test-password"):emailBody(sendChallenge(old),sent.get().code());
        clearInvocations(tokens,hasher);var result=call(old,body);assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));assertNull(result.getResponse().getHeader("Location"));
        var response=json.readTree(result.getResponse().getContentAsString());assertEquals(4,response.size());assertEquals("Bearer",response.get("tokenType").asString());
        String fresh=response.get("accessToken").asString();var claims=payload(fresh);assertEquals(user.getId().toString(),claims.get("sub").asString());assertEquals(login.name(),claims.get("role").asString());assertEquals(0,claims.get("tokenVersion").asLong());assertEquals("REAUTHENTICATION_"+method,claims.get("loginType").asString());
        assertEquals(now.get().getEpochSecond(),claims.get("auth_time").asLong());assertEquals(claims.get("auth_time").asLong(),Instant.parse(response.get("authenticatedAt").asString()).getEpochSecond());assertEquals(claims.get("exp").asLong(),Instant.parse(response.get("expiresAt").asString()).getEpochSecond());
        for(String valid:List.of(old,other,fresh))mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+valid)).andExpect(status().isOk());
        assertEquals(legacy?null:now.get().minusSeconds(600).getEpochSecond(),legacy?null:payload(old).get("auth_time").asLong());if(legacy)assertFalse(payload(old).has("auth_time"));
        assertEquals(before,jdbc.queryForMap("select * from users where id=?",user.getId()));assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));verify(hasher,never()).hash(anyString());
        assertTrue(redis.keys("music:auth:login-ticket:*").isEmpty());if(method.equals("EMAIL_CODE"))assertTrue(active(user).isEmpty());
    }
    static Stream<String> historicalPasswords(){return Stream.of("a","short77"," 中文密码 "," leading and trailing ");}
    @ParameterizedTest @MethodSource("historicalPasswords")
    void should_accept_real_historical_passwords_without_email_or_creation_rule(String password) throws Exception {
        var user=save(UserRole.USER,null,password);assertEquals(200,call(token(user,UserRole.USER,true),passwordBody(password)).getResponse().getStatus());
    }
    @ParameterizedTest @ValueSource(strings={"PASSWORD","EMAIL_CODE"})
    void should_preserve_long_max_version_through_real_send_signing_and_filter_chain(String method) throws Exception {
        var user=save(UserRole.ADMIN,"a@example.com","test-password");jdbc.update("update users set token_version=? where id=?",Long.MAX_VALUE,user.getId());String token=token(user,UserRole.USER,false);
        var body=method.equals("PASSWORD")?passwordBody("test-password"):emailBody(sendChallenge(token),sent.get().code());var result=call(token,body);assertEquals(200,result.getResponse().getStatus());
        String fresh=json.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();assertEquals(Long.MAX_VALUE,payload(fresh).get("tokenVersion").asLong());
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+fresh)).andExpect(status().isOk()).andExpect(jsonPath("$.loginRole").value("USER"));assertEquals(Long.MAX_VALUE,jdbc.queryForObject("select token_version from users where id=?",Long.class,user.getId()));
    }
    @Test void should_preserve_verifier_behavior_for_128_character_password_input() throws Exception {
        var user=save(UserRole.USER,null,"a".repeat(72));String password="a".repeat(128);boolean matches=hasher.matches(password,user.getPasswordHash());
        var result=call(token(user,UserRole.USER,true),passwordBody(password));assertEquals(matches?200:401,result.getResponse().getStatus());if(!matches)assertCode(result,401,"REAUTHENTICATION_FAILED");
    }
    @Test void should_ignore_forged_headers_and_query_and_reject_identity_body_before_quota() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);
        mvc.perform(post(PATH).header("Authorization","Bearer "+token).header("X-User-Id","999").header("X-Auth-Time","9999999999").header("X-Role","ADMIN").param("userId","999").param("method","EMAIL_CODE").contentType("application/json").content(json.writeValueAsString(passwordBody("test-password"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"));
        mvc.perform(post(PATH).header("Authorization","Bearer "+token).contentType("application/json").content("{\"method\":\"PASSWORD\",\"password\":\"test-password\",\"userId\":999}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));
    }
    @ParameterizedTest @ValueSource(strings={"missing","signature","expired","subject","version","role","issuer","audience","revoked","future-auth-time"})
    void should_reject_invalid_actual_jwt_before_business_admission(String damage) throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");long time=now.get().getEpochSecond();Map<String,Object> claims=new HashMap<>(Map.of("iss","music-api","aud",List.of("music-client"),"sub",user.getId().toString(),"iat",time,"exp",time+600,"role","USER","tokenVersion",0));
        switch(damage){case "expired"->{claims.put("iat",time-240);claims.put("exp",time-120);}case "subject"->claims.put("sub","2147483648");case "version"->claims.remove("tokenVersion");case "role"->claims.put("role","OTHER");case "issuer"->claims.put("iss","other");case "audience"->claims.put("aud",List.of("other"));case "revoked"->jdbc.update("update users set token_version=1 where id=?",user.getId());case "future-auth-time"->claims.put("auth_time",time+1);}
        var jwt=new JWSObject(new JWSHeader(JWSAlgorithm.HS256),new Payload(claims));jwt.sign(new MACSigner((damage.equals("signature")?"other-test-secret-that-is-long-enough-123456":"test-secret-that-is-long-enough-1234567890").getBytes(StandardCharsets.UTF_8)));
        var request=post(PATH).contentType("application/json").content(json.writeValueAsString(passwordBody("test-password")));if(!damage.equals("missing"))request.header("Authorization","Bearer "+jwt.serialize());
        mvc.perform(request).andExpect(status().isUnauthorized());assertFalse(Boolean.TRUE.equals(redis.hasKey(quotaKey(user))));verify(tokens,never()).issueReauthenticated(any(),any(),any(),any());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void should_count_initial_unbound_email_and_allow_password_alternative(String email) throws Exception {
        var user=save(UserRole.USER,email,"test-password");String token=token(user,UserRole.USER,false);var challenge=new Challenge("A".repeat(43),"012345");assertCode(call(token,emailBody(challenge,challenge.code())),409,"CURRENT_EMAIL_NOT_BOUND");assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));assertEquals(200,call(token,passwordBody("test-password")).getResponse().getStatus());
    }
    @Test void should_share_ten_admissions_across_modes_and_tokens_and_not_reset_after_resend() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String first=token(user,UserRole.USER,false),second=token(user,UserRole.USER,true);var challenge=sendChallenge(first);
        for(int i=0;i<5;i++)assertCode(call(first,passwordBody("wrong-password")),401,"REAUTHENTICATION_FAILED");
        for(int i=0;i<5;i++)assertCode(call(second,emailBody(challenge,wrongCode(challenge))),401,"REAUTHENTICATION_FAILED");
        assertTrue(active(user).isEmpty());clearCooldown(user);var next=sendChallenge(first);var untouched=active(user);
        assertCode(call(first,emailBody(next,next.code())),429,"REAUTHENTICATION_RATE_LIMITED");assertEquals(untouched,active(user));assertEquals("10",redis.opsForHash().get(quotaKey(user),"count"));
    }
    @ParameterizedTest @CsvSource({"PASSWORD,hash,401,REAUTHENTICATION_FAILED","PASSWORD,version,401,TOKEN_REVOKED","PASSWORD,role,401,TOKEN_REVOKED","PASSWORD,delete,401,TOKEN_REVOKED","PASSWORD,email,200,none","PASSWORD,username,200,none","EMAIL_CODE,hash,200,none","EMAIL_CODE,version,401,TOKEN_REVOKED","EMAIL_CODE,role,401,TOKEN_REVOKED","EMAIL_CODE,delete,401,TOKEN_REVOKED","EMAIL_CODE,email,401,REAUTHENTICATION_FAILED","EMAIL_CODE,unbound,401,REAUTHENTICATION_FAILED"})
    void should_recheck_real_database_binding_after_matching(String method,String change,int status,String code) throws Exception {
        var user=save(UserRole.ADMIN,"a@example.com","test-password");String token=token(user,UserRole.ADMIN,false);Map<String,String> body=method.equals("PASSWORD")?passwordBody("test-password"):emailBody(sendChallenge(token),sent.get().code());String replacement=hasher.hash("replacement-password");
        doAnswer(i->{boolean match=(Boolean)i.callRealMethod();if(match)switch(change){case "hash"->jdbc.update("update users set password_token=? where id=?",replacement,user.getId());case "version"->jdbc.update("update users set token_version=1 where id=?",user.getId());case "role"->jdbc.update("update users set user_role='USER' where id=?",user.getId());case "delete"->jdbc.update("delete from users where id=?",user.getId());case "email"->jdbc.update("update users set email='other@example.com' where id=?",user.getId());case "unbound"->jdbc.update("update users set email=null where id=?",user.getId());case "username"->jdbc.update("update users set username='changed_name' where id=?",user.getId());}return match;}).when(hasher).matches(anyString(),anyString());
        var result=call(token,body);if(status==200)assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());else{assertCode(result,status,code);verify(tokens,never()).issueReauthenticated(any(),any(),any(),any());}
        if(method.equals("EMAIL_CODE"))assertTrue(active(user).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"PASSWORD","EMAIL_CODE"})
    void should_anchor_time_at_match_before_consumption_and_database_delay(String method) throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var body=method.equals("PASSWORD")?passwordBody("test-password"):emailBody(sendChallenge(token),sent.get().code());Instant proof=now.get();var reads=new AtomicInteger();
        doAnswer(i->{var value=mockingDetails(users).getMockCreationSettings().getDefaultAnswer().answer(i);if(reads.incrementAndGet()==2)now.set(proof.plusSeconds(400));return value;}).when(users).findReauthenticationSnapshot(user.getId());
        doAnswer(i->{i.callRealMethod();now.set(proof.plusSeconds(100));return null;}).when(store).consume(any());
        var result=call(token,body);assertEquals(200,result.getResponse().getStatus());var response=json.readTree(result.getResponse().getContentAsString());var claims=payload(response.get("accessToken").asString());assertEquals(proof.getEpochSecond(),claims.get("auth_time").asLong());assertEquals(proof.plusSeconds(400).getEpochSecond(),claims.get("iat").asLong());assertEquals(proof,Instant.parse(response.get("authenticatedAt").asString()));
        mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+response.get("accessToken").asString())).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"admit-before","admit-after","read-before","read-after","fail-before","fail-after","consume-before","consume-after"})
    void should_fail_closed_when_actual_operation_confirmation_is_lost(String fault) throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var challenge=sendChallenge(token);
        org.mockito.stubbing.Answer<Object> failure=i->{if(fault.endsWith("after"))i.callRealMethod();throw new ReauthenticationUnavailableException(new RuntimeException("private dependency details"));};
        if(fault.startsWith("admit"))doAnswer(failure).when(store).admit(user.getId());else if(fault.startsWith("read"))doAnswer(failure).when(store).read(any(),anyString());else if(fault.startsWith("fail"))doAnswer(failure).when(store).recordFailure(any());else doAnswer(failure).when(store).consume(any());
        assertCode(call(token,emailBody(challenge,fault.startsWith("fail")?wrongCode(challenge):challenge.code())),503,"REAUTHENTICATION_UNAVAILABLE");verify(tokens,never()).issueReauthenticated(any(),any(),any(),any());
        if(fault.equals("admit-before"))assertFalse(Boolean.TRUE.equals(redis.hasKey(quotaKey(user))));else assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));
        if(fault.equals("consume-after"))assertTrue(active(user).isEmpty());else assertEquals(fault.equals("fail-after")?"1":"0",active(user).get("attempts"));
    }
    @ParameterizedTest @ValueSource(strings={"initial-database","final-database","sign"})
    void should_keep_consumption_and_quota_on_database_or_signing_failure(String fault) throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var challenge=sendChallenge(token);
        if(fault.equals("initial-database"))doThrow(new DataAccessResourceFailureException("private")).when(users).findReauthenticationSnapshot(user.getId());
        else if(fault.equals("final-database"))doAnswer(i->{i.callRealMethod();doThrow(new DataAccessResourceFailureException("private")).when(users).findReauthenticationSnapshot(user.getId());return null;}).when(store).consume(any());
        else doThrow(new IllegalStateException("private@example.com 012345 secret-hash secret-token")).when(tokens).issueReauthenticated(any(),any(),any(),any());
        var result=call(token,emailBody(challenge,challenge.code()));assertCode(result,fault.equals("sign")?500:503,fault.equals("sign")?"INTERNAL_ERROR":"REAUTHENTICATION_UNAVAILABLE");assertFalse(result.getResponse().getContentAsString().contains("private"));assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));assertEquals(fault.equals("initial-database"),!active(user).isEmpty());
    }
    @Test void should_sign_original_version_when_revocation_happens_after_final_lookup() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);
        doAnswer(i->{assertEquals(0,((JwtTokenSubject)i.getArgument(0)).tokenVersion());jdbc.update("update users set token_version=1 where id=?",user.getId());return i.callRealMethod();}).when(tokens).issueReauthenticated(any(),any(),any(),any());
        var result=call(token,passwordBody("test-password"));assertEquals(200,result.getResponse().getStatus());String fresh=json.readTree(result.getResponse().getContentAsString()).get("accessToken").asString();assertEquals(0,payload(fresh).get("tokenVersion").asLong());mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+fresh)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }
    @Test void should_consume_once_under_concurrent_actual_http_requests() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var challenge=sendChallenge(token);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(6);List<Future<MvcResult>> results=new ArrayList<>();
        try{for(int i=0;i<6;i++)results.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return call(token,emailBody(challenge,challenge.code()));}));start.countDown();int success=0;for(var f:results){var result=f.get(15,TimeUnit.SECONDS);if(result.getResponse().getStatus()==200)success++;else assertCode(result,401,"REAUTHENTICATION_FAILED");}assertEquals(1,success);verify(tokens,times(1)).issueReauthenticated(any(),any(),any(),any());assertEquals("6",redis.opsForHash().get(quotaKey(user),"count"));}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void should_not_mutate_same_code_new_active_with_old_match_or_failure(boolean correct) throws Exception {
        doReturn("012345").when(generator).generate();var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var old=sendChallenge(token);String oldHash=(String)active(user).get("codeHash");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(i->{boolean match=(Boolean)i.callRealMethod();if(oldHash.equals(i.getArgument(1))){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return match;}).when(hasher).matches(anyString(),anyString());
        try{var oldResult=pool.submit(()->call(token,emailBody(old,correct?old.code():wrongCode(old))));assertTrue(entered.await(5,TimeUnit.SECONDS));clearCooldown(user);var fresh=sendChallenge(token);assertEquals(old.code(),fresh.code());var before=active(user);release.countDown();assertCode(oldResult.get(15,TimeUnit.SECONDS),401,"REAUTHENTICATION_FAILED");assertEquals(before,active(user));assertEquals(200,call(token,emailBody(fresh,fresh.code())).getResponse().getStatus());}finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_reject_correct_match_if_fifth_failure_finishes_first() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var challenge=sendChallenge(token);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(i->{boolean match=(Boolean)i.callRealMethod();if(match){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return match;}).when(hasher).matches(anyString(),anyString());
        try{var correct=pool.submit(()->call(token,emailBody(challenge,challenge.code())));assertTrue(entered.await(5,TimeUnit.SECONDS));for(int i=0;i<5;i++)assertCode(call(token,emailBody(challenge,wrongCode(challenge))),401,"REAUTHENTICATION_FAILED");assertTrue(active(user).isEmpty());release.countDown();assertCode(correct.get(15,TimeUnit.SECONDS),401,"REAUTHENTICATION_FAILED");verify(tokens,never()).issueReauthenticated(any(),any(),any(),any());}finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_preserve_active_and_pending_after_password_and_preserve_pending_after_code() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var challenge=sendChallenge(token);clearCooldown(user);
        var sendStore=new ReauthenticationEmailRequestStore(redis);var reservation=sendStore.reserve(new ReauthenticationEmailBinding(user.getId(),"a@example.com",0),"A".repeat(43),"B".repeat(43));String pending="music:auth:reauth-email:pending:"+user.getId();var beforeActive=active(user);var beforePending=redis.opsForHash().entries(pending);
        assertEquals(200,call(token,passwordBody("test-password")).getResponse().getStatus());assertEquals(beforeActive,active(user));assertEquals(beforePending,redis.opsForHash().entries(pending));assertEquals(200,call(token,emailBody(challenge,challenge.code())).getResponse().getStatus());assertTrue(active(user).isEmpty());assertEquals(beforePending,redis.opsForHash().entries(pending));sendStore.cancel(new ReauthenticationEmailBinding(user.getId(),"a@example.com",0),reservation.challengeDigest());
    }
    @Test void should_not_restore_consumed_code_when_response_write_fails() throws Exception {
        var user=save(UserRole.USER,"a@example.com","test-password");String token=token(user,UserRole.USER,false);var challenge=sendChallenge(token);var attempted=new AtomicBoolean();
        jakarta.servlet.Filter disconnect=(request,response,chain)->chain.doFilter(request,new jakarta.servlet.http.HttpServletResponseWrapper((jakarta.servlet.http.HttpServletResponse)response){
            @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return new jakarta.servlet.ServletOutputStream(){@Override public boolean isReady(){return true;}@Override public void setWriteListener(jakarta.servlet.WriteListener listener){}@Override public void write(int value)throws java.io.IOException{attempted.set(true);throw new java.io.IOException("Simulated disconnected response");}};}
        });
        var failing=MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).addFilter(disconnect,PATH).build();
        try{failing.perform(post(PATH).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(emailBody(challenge,challenge.code())))).andReturn();}catch(java.io.IOException|jakarta.servlet.ServletException expected){assertTrue(attempted.get());}
        assertTrue(attempted.get());assertTrue(active(user).isEmpty());verify(tokens,times(1)).issueReauthenticated(any(),any(),any(),any());assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));
    }
}
