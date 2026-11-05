package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetRequestIntegrationTest extends PasswordResetRequestIntegrationSupport {
 @ParameterizedTest @EnumSource(UserRole.class)
 void should_admit_anonymously_and_activate_without_changing_user_or_logging_in(UserRole role)throws Exception{
  var user=save("alice@example.com",role);var before=row(user);var a=accept(" Alice@Example.com ");assertTrue(PasswordResetIdentifiers.isCanonicalDigest(a.id()));assertTrue(sent.isEmpty());assertNull(active("alice@example.com"));assertTrue(processor.processNext());
  assertEquals(a.digest(),active("alice@example.com"));var state=activeState(a);assertEquals("0",state.get("attempts"));assertEquals("0",state.get("tokenVersion"));assertEquals(a.expiresAt().toEpochMilli(),Long.parseLong((String)state.get("expiresAt")));assertEquals(1,sent.size());assertTrue(hasher.matches(sent.getFirst().code(),(String)state.get("codeHash")));assertEquals(before,row(user));assertFalse(processor.processNext());
 }
 @ParameterizedTest @ValueSource(strings={"missing@example.com"," alice@example.com","alice@example.com ","álice@example.com"})
 void should_consume_same_quota_and_capacity_but_not_send_for_unmatched_accounts(String stored)throws Exception{
  if(!stored.equals("missing@example.com"))save(stored,UserRole.USER);var a=accept("alice@example.com");assertEquals(1L,redis.opsForZSet().zCard(P+"outstanding"));assertEquals("1",redis.opsForHash().get(P+"email-daily:"+digest("alice@example.com"),"count"));assertTrue(processor.processNext());assertTrue(sent.isEmpty());assertNull(active("alice@example.com"));assertEquals(0L,redis.opsForZSet().zCard(P+"outstanding"));assertTrue(activeState(a).isEmpty());
 }
 @Test void should_not_rebind_missing_task_to_new_account()throws Exception{accept("alice@example.com");save("alice@example.com",UserRole.USER);assertTrue(processor.processNext());assertTrue(sent.isEmpty());assertNull(active("alice@example.com"));}
 @ParameterizedTest @ValueSource(strings={"version","email","role","delete"})
 void should_not_send_after_binding_changed_during_queue(String change)throws Exception{
  var user=save("alice@example.com",UserRole.USER);accept("alice@example.com");
  switch(change){case "version"->jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());case "email"->jdbc.update("update users set email='other@example.com' where id=?",user.getId());case "role"->jdbc.update("update users set user_role='ADMIN',token_version=token_version+1 where id=?",user.getId());case "delete"->users.delete(user);}
  assertTrue(processor.processNext());assertTrue(sent.isEmpty());assertNull(active("alice@example.com"));
 }
 @Test void should_keep_old_active_until_new_activation_and_discard_failed_new_send()throws Exception{
  save("alice@example.com",UserRole.USER);var first=accept("alice@example.com");processor.processNext();cooldown("alice@example.com");var second=accept("alice@example.com");assertEquals(first.digest(),active("alice@example.com"));
  doThrow(new org.example.music.user.exception.PasswordResetSendFailedException(null)).when(mail).send(any());processor.processNext();assertEquals(first.digest(),active("alice@example.com"));assertTrue(activeState(second).isEmpty());
 }
 @Test void should_reject_bad_bearer_without_requiring_recent_authentication()throws Exception{
  var user=save("alice@example.com",UserRole.USER);String valid=token(user);
  mvc.perform(post(PATH).header("Authorization","Bearer malformed").contentType("application/json").content("{\"email\":\"alice@example.com\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post(PATH).header("Authorization","Bearer "+valid).contentType("application/json").content("{\"email\":\"alice@example.com\"}")).andExpect(status().isAccepted());
  jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());
  mvc.perform(post(PATH).header("Authorization","Bearer "+valid).contentType("application/json").content("{\"email\":\"alice@example.com\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
 }
 @Test void should_use_only_remote_ip_and_generate_complete_openapi()throws Exception{
  mvc.perform(post(PATH).header("X-Forwarded-For","forged").header("X-Trace-Id","trace.with.dots").contentType("application/json").content("{\"email\":\"missing@example.com\"}")).andExpect(status().isAccepted());
  assertTrue(redis.hasKey(P+"ip:"+digest("127.0.0.1")));assertFalse(redis.hasKey(P+"ip:"+digest("forged")));
  var doc=json.readTree(mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token(save("docs@example.com",UserRole.USER)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  var responses=doc.get("paths").get(PATH).get("post").get("responses");assertEquals(8,responses.size());for(var status:List.of("202","400","401","403","415","429","500","503"))assertNotNull(responses.get(status));
  var schema=doc.get("components").get("schemas").get("PasswordResetRequest");assertEquals(1,schema.get("properties").size());assertEquals(120,schema.get("properties").get("email").get("maxLength").asInt());
 }
 @Test void should_reject_actual_expired_and_invalid_signature_tokens_before_admission()throws Exception{
  var user=save("alice@example.com",UserRole.USER);var now=java.time.Instant.now();
  var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("music-api").audience(List.of("music-client")).subject(user.getId().toString()).issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300)).claim("role","USER").claim("tokenVersion",0L).build();
  String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
  String valid=token(user);String[] parts=valid.split("\\.");String invalid=parts[0]+"."+parts[1]+"."+"A".repeat(43);
  for(String value:List.of(expired,invalid))mvc.perform(post(PATH).header("Authorization","Bearer "+value).contentType("application/json").content("{\"email\":\"alice@example.com\"}")).andExpect(status().isUnauthorized());
  assertFalse(redis.hasKey(P+"email-daily:"+digest("alice@example.com")));verifyNoInteractions(mail);
 }
}
