package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class EmailChangeConfirmationIntegrationTest extends EmailChangeConfirmationIntegrationSupport {
    String login(UserEntity user,String account,String type,UserRole role) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType",type,"account",account,"password","test-password")))).andExpect(status().isOk()).andReturn();
        var response=json.readTree(result.getResponse().getContentAsString());
        if(response.get("status").asString().equals("ROLE_SELECTION_REQUIRED")){
            result=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","ROLE_SELECTION","loginTicket",response.get("roleSelection").get("loginTicket").asString(),"loginRole",role.name())))).andExpect(status().isOk()).andReturn();response=json.readTree(result.getResponse().getContentAsString());
        }
        return response.get("authentication").get("accessToken").asString();
    }
    @ParameterizedTest @CsvSource({"USER,USER","ADMIN,ADMIN","ADMIN,USER"})
    void should_commit_email_and_revoke_all_old_logins_without_issuing_token(UserRole account,UserRole role) throws Exception {
        var user=save(account," Old@Example.com ");String current=token(user,role,60L),other=token(user,role,10L);var before=row(user);var challenge=send(current," New@Example.com ");
        var result=confirm(current,challenge);assertEquals(204,result.getResponse().getStatus(),result.getResponse().getContentAsString());assertEquals("",result.getResponse().getContentAsString());assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));assertNull(result.getResponse().getHeader("Location"));
        var after=row(user);assertEquals("new@example.com",after.get("email"));assertEquals(1L,((Number)after.get("token_version")).longValue());
        for(String key:before.keySet())if(!Set.of("email","token_version","updated_at").contains(key))assertEquals(before.get(key),after.get(key),key);
        verify(tokens,never()).issue(any(),any(),any(),any());verify(tokens,never()).issueReauthenticated(any(),any(),any(),any());
        assertOrdinaryAccess(current,401);assertOrdinaryAccess(other,401);assertCode(confirm(current,challenge),401,"TOKEN_REVOKED");
        String fresh=login(user,"new@example.com","EMAIL_PASSWORD",role);assertOrdinaryAccess(fresh,200);assertCode(confirm(fresh,challenge),400,"EMAIL_CHANGE_VERIFICATION_FAILED");
        assertOrdinaryAccess(login(user,user.getUsername(),"USERNAME_PASSWORD",role),200);assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void should_complete_first_binding(String email) throws Exception {var user=save(UserRole.USER,email);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");assertEquals(204,confirm(value,c).getResponse().getStatus());assertEquals("new@example.com",row(user).get("email"));assertEquals(1L,((Number)row(user).get("token_version")).longValue());}
    @ParameterizedTest @ValueSource(longs={299,300,301})
    void should_check_real_auth_time_inclusive_boundary(long age) throws Exception {var user=save(UserRole.USER,"old@example.com");String fresh=token(user,UserRole.USER,0L);var c=send(fresh,"new@example.com");String value=token(user,UserRole.USER,age);var result=confirm(value,c);if(age<=300)assertEquals(204,result.getResponse().getStatus());else{assertCode(result,401,"REAUTHENTICATION_REQUIRED");assertFalse(Boolean.TRUE.equals(redis.hasKey(quotaKey(user))));}}
    @Test void should_check_recent_gate_before_parsing_and_keep_legacy_login_usable() throws Exception {var user=save(UserRole.USER,null);String legacy=token(user,UserRole.USER,null);mvc.perform(put(PATH).header("Authorization","Bearer "+legacy).contentType("text/plain").content("invalid")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));assertOrdinaryAccess(legacy,200);assertFalse(Boolean.TRUE.equals(redis.hasKey(quotaKey(user))));verifyNoInteractions(mail);}
    @Test void should_not_recheck_window_after_hashing() throws Exception {var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");doAnswer(i->{now.set(now.get().plusSeconds(301));return i.callRealMethod();}).when(hasher).matches(eq(c.code()),anyString());assertEquals(204,confirm(value,c).getResponse().getStatus());}
    @Test void should_expire_after_five_wrong_codes_and_keep_original_deadline() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var initial=active(user);var before=row(user);
        for(int i=1;i<=5;i++){assertCode(confirm(value,c,wrongCode(c)),400,"EMAIL_CHANGE_VERIFICATION_FAILED");if(i<5){assertEquals(Integer.toString(i),active(user).get("attempts"));assertEquals(initial.get("expiresAtMs"),active(user).get("expiresAtMs"));}}
        assertTrue(active(user).isEmpty());assertEquals(before,row(user));assertCode(confirm(value,c),400,"EMAIL_CHANGE_VERIFICATION_FAILED");
    }
    @Test void should_not_affect_other_user_or_current_active_when_challenge_is_wrong() throws Exception {
        var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var own=send(value,"own@example.com");var other=save(UserRole.USER,null);var theirs=send(token(other,UserRole.USER,0L),"other@example.com");var a=active(user);var b=active(other);
        assertCode(confirm(value,theirs),400,"EMAIL_CHANGE_VERIFICATION_FAILED");assertEquals(a,active(user));assertEquals(b,active(other));assertEquals(204,confirm(value,own).getResponse().getStatus());
    }
    @Test void should_limit_ten_attempts_and_not_reset_after_new_tokens_or_resends() throws Exception {
        var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var nonexistent=new Challenge("A".repeat(43),c.code(),c.email());
        for(int i=0;i<10;i++)assertCode(confirm(i%2==0?value:token(user,UserRole.USER,0L),nonexistent),400,"EMAIL_CHANGE_VERIFICATION_FAILED");
        var quota=redis.opsForHash().entries(quotaKey(user));var result=confirm(value,c);assertCode(result,429,"EMAIL_CHANGE_CONFIRMATION_RATE_LIMITED");assertTrue(Long.parseLong(result.getResponse().getHeader("Retry-After"))>=1);assertEquals(quota,redis.opsForHash().entries(quotaKey(user)));assertEquals("0",active(user).get("attempts"));
        clearSendCooldown(user);send(value,"other@example.com");assertEquals(quota,redis.opsForHash().entries(quotaKey(user)));
    }
    @Test void should_reject_old_active_version_with_valid_new_token() throws Exception {var user=save(UserRole.USER,null);String old=token(user,UserRole.USER,0L);var c=send(old,"new@example.com");jdbc.update("update users set token_version=1 where id=?",user.getId());String fresh=token(user,UserRole.USER,0L);clearInvocations(hasher);assertCode(confirm(fresh,c),400,"EMAIL_CHANGE_VERIFICATION_FAILED");verify(hasher,never()).matches(anyString(),anyString());assertEquals("0",active(user).get("attempts"));}
    @ParameterizedTest @ValueSource(strings={"email","version","role","delete"})
    void should_reject_changed_account_without_consuming_application(String change) throws Exception {
        var user=save(UserRole.ADMIN,"old@example.com");String value=token(user,UserRole.ADMIN,0L);var c=send(value,"new@example.com");
        switch(change){case "email"->jdbc.update("update users set email='changed@example.com' where id=?",user.getId());case "version"->jdbc.update("update users set token_version=1 where id=?",user.getId());case "role"->jdbc.update("update users set user_role='USER' where id=?",user.getId());case "delete"->jdbc.update("delete from users where id=?",user.getId());}
        assertCode(confirm(value,c),change.equals("email")?409:401,change.equals("email")?"EMAIL_CHANGE_CONFLICT":"TOKEN_REVOKED");assertFalse(active(user).isEmpty());
    }
    @Test void should_preserve_application_if_target_is_occupied_before_consumption() throws Exception {var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"resume@example.com");save(UserRole.USER,"résumé@example.com");assertCode(confirm(value,c),409,"EMAIL_ALREADY_EXISTS");assertFalse(active(user).isEmpty());assertEquals(0L,((Number)row(user).get("token_version")).longValue());}
    @Test void should_reject_max_version_before_consumption() throws Exception {var user=save(UserRole.USER,null);jdbc.update("update users set token_version=? where id=?",Long.MAX_VALUE,user.getId());String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");assertCode(confirm(value,c),503,"EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE");assertFalse(active(user).isEmpty());assertEquals(Long.MAX_VALUE,((Number)row(user).get("token_version")).longValue());}
    @ParameterizedTest @ValueSource(strings={"no-ttl","bad-attempts","bad-address"})
    void should_fail_closed_on_damaged_active(String damage) throws Exception {var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");switch(damage){case "no-ttl"->redis.persist(activeKey(user));case "bad-attempts"->redis.opsForHash().put(activeKey(user),"attempts","01");case "bad-address"->redis.opsForHash().put(activeKey(user),"newEmail"," New@Example.com ");}assertCode(confirm(value,c),503,"EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE");assertNull(row(user).get("email"));}
    @Test void should_require_valid_body_and_media_type_without_spending_quota() throws Exception {var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);mvc.perform(put(PATH).header("Authorization","Bearer "+value).contentType("application/json").content("{}")).andExpect(status().isBadRequest());mvc.perform(put(PATH).header("Authorization","Bearer "+value).contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());mvc.perform(put(PATH).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());assertFalse(Boolean.TRUE.equals(redis.hasKey(quotaKey(user))));}
    @Test void should_generate_openapi_with_two_inputs_and_empty_204() throws Exception {var user=save(UserRole.USER,null);var result=mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token(user,UserRole.USER,0L))).andExpect(status().isOk()).andReturn();var doc=json.readTree(result.getResponse().getContentAsString());var operation=doc.get("paths").get(PATH).get("put");for(String code:List.of("204","400","401","403","409","415","429","500","503"))assertTrue(operation.get("responses").has(code),code);assertFalse(operation.get("responses").get("204").has("content"));var properties=doc.get("components").get("schemas").get("EmailChangeConfirmationRequest").get("properties");assertEquals(2,properties.size());assertEquals(43,properties.get("challengeId").get("maxLength").asInt());assertEquals(6,properties.get("code").get("maxLength").asInt());}
}
