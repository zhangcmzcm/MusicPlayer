package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.LoginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetConfirmationIntegrationTest extends PasswordResetConfirmationIntegrationSupport {
    String login(UserEntity user,String account,String type,String password,UserRole role) throws Exception {
        var result=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType",type,"account",account,"password",password)))).andReturn();
        assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        var response=json.readTree(result.getResponse().getContentAsString());
        if(response.get("status").asString().equals("ROLE_SELECTION_REQUIRED")) {
            result=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","ROLE_SELECTION","loginTicket",response.get("roleSelection").get("loginTicket").asString(),"loginRole",role.name())))).andReturn();
            assertEquals(200,result.getResponse().getStatus(),result.getResponse().getContentAsString()); response=json.readTree(result.getResponse().getContentAsString());
        }
        return response.get("authentication").get("accessToken").asString();
    }
    int access(String token) throws Exception { return mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus(); }
    @ParameterizedTest @EnumSource(UserRole.class)
    void should_reset_and_revoke_target_logins_and_allow_normal_login(UserRole role) throws Exception {
        var user=save("alice@example.com",role); String old=token(user); var before=row(user); var ready=issue(ready(user.getEmail()));
        var result=confirm(ready," new-password "); assertEquals(204,result.getResponse().getStatus(),result.getResponse().getContentAsString());
        assertEquals("",result.getResponse().getContentAsString()); assertNull(result.getResponse().getHeader("Location"));
        assertEquals("no-store",result.getResponse().getHeader("Cache-Control")); var after=row(user);
        assertEquals(1L,((Number)after.get("token_version")).longValue()); assertTrue(hasher.matches(" new-password ",(String)after.get("password_token")));
        for(String key:before.keySet()) if(!Set.of("password_token","token_version","updated_at").contains(key)) assertEquals(before.get(key),after.get(key),key);
        assertEquals(401,access(old)); assertProblem(confirm(ready,"new-password2"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertEquals(200,access(login(user,user.getEmail(),"EMAIL_PASSWORD"," new-password ",role)));
        assertEquals(200,access(login(user,user.getUsername(),"USERNAME_PASSWORD"," new-password ",UserRole.USER)));
        var wrong=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","USERNAME_PASSWORD","account",user.getUsername(),"password","test-password")))).andReturn();
        assertEquals(401,wrong.getResponse().getStatus());
    }
    @Test void should_ignore_other_valid_token_as_target() throws Exception {
        var a=save("a@example.com",UserRole.USER); var b=save("b@example.com",UserRole.USER); String token=token(a); var before=row(a); var ready=issue(ready(b.getEmail()));
        var result=mvc.perform(post(CONFIRM).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(Map.of("resetTicket",ready.resetTicket(),"newPassword","new-password")))).andReturn();
        assertEquals(204,result.getResponse().getStatus(),result.getResponse().getContentAsString()); assertEquals(before,row(a)); assertEquals(200,access(token));
        assertEquals(1L,((Number)row(b).get("token_version")).longValue());
    }
    @Test void should_preserve_ticket_on_equivalent_password() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=issue(ready(user.getEmail())); var before=ticketState(ready);
        long ending=tickets.readByDigest(digest(ready.resetTicket())).orElseThrow().period().expiresAt().toEpochMilli();
        assertProblem(confirm(ready,"test-password"),400,"NEW_PASSWORD_SAME_AS_OLD"); assertEquals(before,ticketState(ready));
        assertEquals(ending,redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(org.example.music.user.repository.PasswordResetTicketStore.PREFIX+"ticket:"+digest(ready.resetTicket()))));
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus());
    }
    @Test void should_hide_account_limit_across_reissues_like_unknown_ticket() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var first=issue(ready(user.getEmail()));
        for(int attempt=0;attempt<5;attempt++) assertProblem(confirm(first,"test-password"),400,"NEW_PASSWORD_SAME_AS_OLD");
        cooldown(user.getEmail());var second=issue(ready(user.getEmail()));
        for(int attempt=0;attempt<5;attempt++) assertProblem(confirm(second,"test-password"),400,"NEW_PASSWORD_SAME_AS_OLD");
        redis.delete("music:auth:password-reset-completion:ip:"+digest("127.0.0.1"));
        var rejected=confirm(second,"new-password");assertProblem(rejected,400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertNull(rejected.getResponse().getHeader("Retry-After"));
        var missing=confirm(new Issued(PasswordResetIdentifiers.generate(),java.time.Instant.now().plusSeconds(300)),"new-password");
        assertProblem(missing,400,"PASSWORD_RESET_VERIFICATION_FAILED");
        var one=json.readTree(rejected.getResponse().getContentAsString());var two=json.readTree(missing.getResponse().getContentAsString());
        for(String field:List.of("code","type","title","detail")) assertEquals(one.get(field),two.get(field));
        assertEquals("10",redis.opsForHash().get("music:auth:password-reset-completion:user:"+user.getId(),"count"));
        assertFalse(ticketState(second).isEmpty());
    }

    @Test void should_limit_only_ip_with_429_and_skip_account_keys_for_unknown_ids() throws Exception {
        var fake=new Issued(PasswordResetIdentifiers.generate(),java.time.Instant.now().plusSeconds(300));
        for(int i=0;i<10;i++) assertProblem(confirm(fake,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        var result=confirm(fake,"new-password"); assertProblem(result,429,"PASSWORD_RESET_CONFIRMATION_RATE_LIMITED");
        assertTrue(Long.parseLong(result.getResponse().getHeader("Retry-After"))>=1); assertTrue(redis.keys("music:auth:password-reset-completion:user:*").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"email","role","version","delete"})
    void should_reject_changed_binding_without_consumption(String change) throws Exception {
        var user=save("a@example.com",UserRole.ADMIN); var ready=issue(ready(user.getEmail()));
        switch(change) {
            case "email" -> jdbc.update("update users set email='A@example.com' where id=?",user.getId());
            case "role" -> jdbc.update("update users set user_role='USER' where id=?",user.getId());
            case "version" -> jdbc.update("update users set token_version=1 where id=?",user.getId());
            case "delete" -> jdbc.update("delete from users where id=?",user.getId());
        }
        assertProblem(confirm(ready,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED"); assertFalse(ticketState(ready).isEmpty());
    }
    @Test void should_reject_max_version_before_consumption() throws Exception {
        var user=save("a@example.com",UserRole.USER); jdbc.update("update users set token_version=? where id=?",Long.MAX_VALUE,user.getId()); var ready=issue(ready(user.getEmail()));
        assertProblem(confirm(ready,"new-password"),503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE"); assertFalse(ticketState(ready).isEmpty());
        verify(updater,never()).update(any(),anyString());
    }
    @Test void should_keep_public_route_strict_and_other_routes_protected() throws Exception {
        mvc.perform(post(CONFIRM).contentType("application/json").content("{}")).andDo(r->assertProblem(r,400,"VALIDATION_FAILED"));
        mvc.perform(post(CONFIRM).contentType("text/plain").content("{}")).andDo(r->assertProblem(r,415,"UNSUPPORTED_MEDIA_TYPE"));
        assertTrue(redis.keys("music:auth:password-reset-completion:*").isEmpty());
        assertEquals(401,mvc.perform(get("/api/v1/users/me")).andReturn().getResponse().getStatus());
    }
    @Test void should_generate_two_field_openapi_and_empty_204() throws Exception {
        var user=save("a@example.com",UserRole.USER); var result=mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token(user))).andReturn();
        assertEquals(200,result.getResponse().getStatus()); var doc=json.readTree(result.getResponse().getContentAsString());
        var responses=doc.get("paths").get(CONFIRM).get("post").get("responses");
        for(String code:List.of("204","400","401","403","415","429","500","503")) assertTrue(responses.has(code),code);
        assertFalse(responses.get("204").has("content"));
        var properties=doc.get("components").get("schemas").get("PasswordResetConfirmationRequest").get("properties");
        assertEquals(2,properties.size()); assertTrue(properties.get("resetTicket").get("writeOnly").asBoolean()); assertTrue(properties.get("newPassword").get("writeOnly").asBoolean());
        assertEquals(72,properties.get("newPassword").get("maxLength").asInt());
    }
    @Test void should_reject_expired_revoked_and_invalid_bearer_before_quota() throws Exception {
        var user=save("a@example.com",UserRole.USER); String revoked=token(user);
        jdbc.update("update users set token_version=1 where id=?",user.getId()); var ready=issue(ready(user.getEmail()));
        var now=java.time.Instant.now();
        String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("music-api").audience(List.of("music-client"))
                        .subject(user.getId().toString()).issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300))
                        .claim("role","USER").claim("tokenVersion",1L).claim("loginType","USERNAME_PASSWORD").build())).getTokenValue();
        for(String token:List.of(revoked,expired,"invalid.jwt.signature")) {
            var result=mvc.perform(post(CONFIRM).header("Authorization","Bearer "+token).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("resetTicket",ready.resetTicket(),"newPassword","new-password")))).andReturn();
            assertEquals(401,result.getResponse().getStatus());
        }
        assertTrue(redis.keys("music:auth:password-reset-completion:*").isEmpty());
        assertFalse(ticketState(ready).isEmpty()); verify(updater,never()).update(any(),anyString());
    }
    @Test void should_invalidate_old_role_selection_ticket_after_reset() throws Exception {
        var user=save("a@example.com",UserRole.ADMIN);
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","USERNAME_PASSWORD","account",user.getUsername(),"password","test-password")))).andReturn();
        assertEquals(200,login.getResponse().getStatus());
        String ticket=json.readTree(login.getResponse().getContentAsString()).get("roleSelection").get("loginTicket").asString();
        var oldAdmin=token(user);
        var oldUser=org.example.music.user.domain.UserRole.USER;
        String alternate=tokens.issue(users.findAuthorizationSnapshot(user.getId()).orElseThrow(),oldUser,LoginType.USERNAME_PASSWORD,java.time.Instant.now()).tokenValue();
        var ready=issue(ready(user.getEmail())); assertEquals(204,confirm(ready,"new-password").getResponse().getStatus());
        assertEquals(401,access(oldAdmin)); assertEquals(401,access(alternate));
        var result=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","ROLE_SELECTION","loginTicket",ticket,"loginRole","ADMIN")))).andReturn();
        assertEquals(401,result.getResponse().getStatus(),result.getResponse().getContentAsString());
    }
    @Test void should_reject_unknown_ticket_without_password_comparison() throws Exception {
        var fake=new Issued(PasswordResetIdentifiers.generate(),java.time.Instant.now().plusSeconds(300));
        clearInvocations(hasher);
        assertProblem(confirm(fake,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        verifyNoInteractions(hasher,updater);
        verify(tickets,never()).consume(any());
    }

    @Test void should_skip_password_work_for_account_denial_stale_binding_and_ip_rejection() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=issue(ready(user.getEmail()));
        for(int i=0;i<10;i++) assertTrue(quota.admitAccount(user.getId()));
        clearInvocations(hasher);
        assertProblem(confirm(live,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");verifyNoInteractions(hasher);
        redis.delete("music:auth:password-reset-completion:user:"+user.getId());
        jdbc.update("update users set email='changed@example.com' where id=?",user.getId());clearInvocations(hasher);
        assertProblem(confirm(live,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");verifyNoInteractions(hasher);
        var ipKey="music:auth:password-reset-completion:ip:"+digest("127.0.0.1");
        redis.delete(ipKey);for(int i=0;i<10;i++) quota.admitIp(digest("127.0.0.1"));
        assertProblem(confirm(live,"new-password"),429,"PASSWORD_RESET_CONFIRMATION_RATE_LIMITED");verifyNoInteractions(hasher);
        assertFalse(ticketState(live).isEmpty());verify(updater,never()).update(any(),anyString());
    }

    @Test void should_increment_version_above_double_integer_precision_exactly() throws Exception {
        var user=save("large@example.com",UserRole.USER);
        jdbc.update("update users set token_version=? where id=?",9007199254740993L,user.getId());
        var issued=issue(ready(user.getEmail()));
        assertEquals("9007199254740993",ticketState(issued).get("tokenVersion"));
        assertEquals(204,confirm(issued,"new-password").getResponse().getStatus());
        assertEquals(9007199254740994L,((Number)row(user).get("token_version")).longValue());
    }

}
