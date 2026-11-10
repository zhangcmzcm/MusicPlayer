package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.LoginType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetVerificationIntegrationTest extends PasswordResetVerificationIntegrationSupport {
    @ParameterizedTest @EnumSource(UserRole.class)
    void should_issue_for_user_and_admin_without_password_or_login_mutation(UserRole role) throws Exception {
        var user=save("ALICE@example.com",role);var oldToken=token(user);var before=row(user);var ready=ready("alice@example.com");
        clearInvocations(tokens,hasher,users);var result=verifyCode(ready);var secret=issuedTicket(result);assertTrue(PasswordResetIdentifiers.isCanonicalDigest(secret));
        var ticket=tickets.readCurrent(user.getId()).orElseThrow();assertEquals(digest(secret),ticket.digest());assertEquals("ALICE@example.com",ticket.account().email());
        assertEquals(Instant.parse(json.readTree(result.getResponse().getContentAsString()).get("expiresAt").asString()),ticket.period().expiresAt());
        assertEquals(ticket.period().expiresAt().toEpochMilli(),expiry(T+"active:"+user.getId()));assertEquals(expiry(T+"active:"+user.getId()),expiry(T+"ticket:"+ticket.digest()));
        assertEquals(before,row(user));assertTrue(activeState(ready.accepted()).isEmpty());
        verify(users,never()).findPasswordResetPreparedSnapshot(anyInt());verify(users,never()).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
        verify(tokens,never()).issue(any(),any(),any(),any());verify(tokens,never()).issueReauthenticated(any(),any(),any(),any());
        verify(hasher,times(1)).matches(eq(ready.code()),anyString());verify(hasher,never()).hash(anyString());
        assertEquals(200,access(oldToken));assertProblem(verifyCode(ready),400,"PASSWORD_RESET_VERIFICATION_FAILED");assertEquals(ticket,tickets.readCurrent(user.getId()).orElseThrow());
    }
    @Test void should_leave_old_role_selection_ticket_usable() throws Exception {
        var user=save("a@example.com",UserRole.ADMIN);
        var login=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","USERNAME_PASSWORD","account",user.getUsername(),"password","test-password")))).andReturn();
        assertEquals(200,login.getResponse().getStatus());String loginTicket=json.readTree(login.getResponse().getContentAsString()).get("roleSelection").get("loginTicket").asString();
        var before=row(user);issuedTicket(verifyCode(ready(user.getEmail())));assertEquals(before,row(user));
        var selected=mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("loginType","ROLE_SELECTION","loginTicket",loginTicket,"loginRole","ADMIN")))).andReturn();
        assertEquals(200,selected.getResponse().getStatus(),selected.getResponse().getContentAsString());assertEquals(0L,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_use_challenge_owner_with_another_valid_bearer() throws Exception {
        var a=save("a@example.com",UserRole.USER);var b=save("b@example.com",UserRole.ADMIN);String bearer=token(a);var beforeA=row(a);var beforeB=row(b);var ready=ready(b.getEmail());
        var result=mvc.perform(post(VERIFY).header("Authorization","Bearer "+bearer).contentType("application/json").content(json.writeValueAsString(Map.of("challengeId",ready.accepted().id(),"code",ready.code())))).andReturn();
        String secret=issuedTicket(result);assertEquals(digest(secret),tickets.readCurrent(b.getId()).orElseThrow().digest());assertTrue(tickets.readCurrent(a.getId()).isEmpty());
        assertEquals(beforeA,row(a));assertEquals(beforeB,row(b));assertEquals(200,access(bearer));
    }
    @Test void should_keep_verification_and_completion_ip_windows_independent() throws Exception {
        var fake=new Ready(new Accepted(PasswordResetIdentifiers.generate(),Instant.now().plusSeconds(300)),"012345");
        var ticket=new Issued(PasswordResetIdentifiers.generate(),Instant.now().plusSeconds(300));
        for(int i=0;i<10;i++) assertProblem(verifyCode(fake),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        long ending=expiry("music:auth:password-reset-confirmation:ip:"+digest("127.0.0.1"));
        assertProblem(verifyCode(fake),429,"PASSWORD_RESET_VERIFICATION_RATE_LIMITED");
        assertTrue(redis.keys("music:auth:password-reset-completion:*").isEmpty());
        for(int i=0;i<10;i++) assertProblem(confirm(ticket,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertProblem(confirm(ticket,"new-password"),429,"PASSWORD_RESET_CONFIRMATION_RATE_LIMITED");
        assertEquals(ending,expiry("music:auth:password-reset-confirmation:ip:"+digest("127.0.0.1")));
        assertEquals("10",redis.opsForHash().get("music:auth:password-reset-confirmation:ip:"+digest("127.0.0.1"),"count"));
        assertTrue(redis.keys("music:auth:password-reset-completion:user:*").isEmpty());
    }

    @Test void should_keep_verification_account_budget_across_reissues_without_revealing_wait() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        for(int issue=0;issue<2;issue++) {
            cooldown(user.getEmail());var live=ready(user.getEmail());String wrong=live.code().equals("000000")?"000001":"000000";
            for(int i=0;i<5;i++) { var bad=new Ready(live.accepted(),wrong);assertProblem(verifyCode(bad),400,"PASSWORD_RESET_VERIFICATION_FAILED"); }
        }
        clearIpWindow();cooldown(user.getEmail());var live=ready(user.getEmail());clearInvocations(hasher);
        var denied=verifyCode(live);assertProblem(denied,400,"PASSWORD_RESET_VERIFICATION_FAILED");assertNull(denied.getResponse().getHeader("Retry-After"));
        verify(hasher,times(1)).matches(eq(live.code()),anyString());assertEquals("0",activeState(live.accepted()).get("attempts"));
        var fake=accept("missing@example.com");assertTrue(processor.processNext());var missing=verifyCode(new Ready(fake,"000000"));assertProblem(missing,400,"PASSWORD_RESET_VERIFICATION_FAILED");
        var first=json.readTree(denied.getResponse().getContentAsString());var second=json.readTree(missing.getResponse().getContentAsString());
        assertEquals(first.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()),second.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        for(String field:List.of("code","type","title","detail"))assertEquals(first.get(field),second.get(field));
        assertEquals("10",redis.opsForHash().get("music:auth:password-reset-confirmation:user:"+user.getId(),"count"));assertTrue(tickets.readCurrent(user.getId()).isEmpty());
    }
    @Test void should_ignore_true_dummy_result_and_skip_crypto_on_ip_denial() throws Exception {
        var fake=new Ready(new Accepted(PasswordResetIdentifiers.generate(),Instant.now().plusSeconds(300)),"012345");clearInvocations(hasher);
        doReturn(true).when(hasher).matches(eq("012345"),anyString());assertProblem(verifyCode(fake),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        verify(hasher,times(1)).matches(eq("012345"),anyString());verify(tickets,never()).exchange(any(),any(),anyString());
        for(int i=1;i<10;i++)confirmations.admitIp(digest("127.0.0.1"));clearInvocations(hasher);
        assertProblem(verifyCode(fake),429,"PASSWORD_RESET_VERIFICATION_RATE_LIMITED");verifyNoInteractions(hasher);
    }
    @Test void should_preserve_ticket_during_new_mail_and_replace_only_after_successful_verification() throws Exception {
        var user=save("a@example.com",UserRole.USER);issuedTicket(verifyCode(ready(user.getEmail())));var old=tickets.readCurrent(user.getId()).orElseThrow();
        cooldown(user.getEmail());var next=accept(user.getEmail());assertEquals(old,tickets.readCurrent(user.getId()).orElseThrow());assertTrue(processor.processNext());
        assertEquals(old,tickets.readCurrent(user.getId()).orElseThrow());String code=sent.getLast().code();String wrong=code.equals("000000")?"000001":"000000";
        assertProblem(verifyCode(new Ready(next,wrong)),400,"PASSWORD_RESET_VERIFICATION_FAILED");assertEquals(old,tickets.readCurrent(user.getId()).orElseThrow());
        var secret=issuedTicket(verifyCode(new Ready(next,code)));assertNotEquals(old.digest(),digest(secret));assertFalse(Boolean.TRUE.equals(redis.hasKey(T+"ticket:"+old.digest())));
    }
    @ParameterizedTest @ValueSource(strings={"email","role","version","delete"})
    void should_reject_changed_binding_before_real_code_processing(String change) throws Exception {
        var user=save("a@example.com",UserRole.ADMIN);var ready=ready(user.getEmail());
        switch(change) {
            case "email" -> jdbc.update("update users set email='A@example.com' where id=?",user.getId());
            case "role" -> jdbc.update("update users set user_role='USER' where id=?",user.getId());
            case "version" -> jdbc.update("update users set token_version=1 where id=?",user.getId());
            case "delete" -> jdbc.update("delete from users where id=?",user.getId());
        }
        clearInvocations(hasher);assertProblem(verifyCode(ready),400,"PASSWORD_RESET_VERIFICATION_FAILED");verify(hasher,times(1)).matches(eq(ready.code()),anyString());
        assertEquals("0",activeState(ready.accepted()).get("attempts"));verify(tickets,never()).exchange(any(),any(),anyString());
    }
    @Test void should_bind_long_max_without_updating_version() throws Exception {
        var user=save("a@example.com",UserRole.USER);jdbc.update("update users set token_version=? where id=?",Long.MAX_VALUE,user.getId());
        issuedTicket(verifyCode(ready(user.getEmail())));assertEquals(Long.MAX_VALUE,tickets.readCurrent(user.getId()).orElseThrow().account().tokenVersion());
        assertEquals(Long.MAX_VALUE,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_reject_invalid_expired_or_revoked_signed_bearer_before_quota() throws Exception {
        var user=save("a@example.com",UserRole.USER);String revoked=token(user);jdbc.update("update users set token_version=1 where id=?",user.getId());var live=ready(user.getEmail());
        var now=Instant.now();String expired=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder()
                .issuer("music-api").audience(List.of("music-client")).subject(user.getId().toString()).id(UUID.randomUUID().toString())
                .issuedAt(now.minusSeconds(600)).expiresAt(now.minusSeconds(300)).claim("role","USER").claim("tokenVersion",1L).claim("loginType","USERNAME_PASSWORD").build())).getTokenValue();
        String valid=token(user);int signatureStart=valid.lastIndexOf('.')+1;char old=valid.charAt(signatureStart);
        String badSignature=valid.substring(0,signatureStart)+(old=='A'?'B':'A')+valid.substring(signatureStart+1);
        for(String value:List.of(revoked,expired,badSignature)) {
            var result=mvc.perform(post(VERIFY).header("Authorization","Bearer "+value).contentType("application/json").content(json.writeValueAsString(Map.of("challengeId",live.accepted().id(),"code",live.code())))).andReturn();
            assertEquals(401,result.getResponse().getStatus());
        }
        assertTrue(redis.keys("music:auth:password-reset-confirmation:*").isEmpty());assertEquals("0",activeState(live.accepted()).get("attempts"));
    }
    @Test void should_reject_legacy_body_at_both_endpoints_without_quota_and_complete_ticket_flow() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        var legacy=json.writeValueAsString(Map.of("challengeId",live.accepted().id(),"code",live.code(),"newPassword","new-password"));
        for(String path:List.of(VERIFY,CONFIRM)) assertProblem(mvc.perform(post(path).contentType("application/json").content(legacy)).andReturn(),400,"VALIDATION_FAILED");
        assertTrue(redis.keys("music:auth:password-reset-confirmation:*").isEmpty());
        assertTrue(redis.keys("music:auth:password-reset-completion:*").isEmpty());
        assertEquals(204,confirm(issue(live),"new-password").getResponse().getStatus());
        assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }

    @Test void should_generate_openapi_for_new_two_field_contract_and_existing_routes() throws Exception {
        var user=save("a@example.com",UserRole.USER);var result=mvc.perform(get("/v3/api-docs").header("Authorization","Bearer "+token(user))).andReturn();assertEquals(200,result.getResponse().getStatus());
        var doc=json.readTree(result.getResponse().getContentAsString());var responses=doc.get("paths").get(VERIFY).get("post").get("responses");
        for(String status:List.of("200","400","401","403","415","429","500","503"))assertTrue(responses.has(status),status);
        var schemas=doc.get("components").get("schemas");var input=schemas.get("PasswordResetVerificationRequest").get("properties");assertEquals(2,input.size());assertTrue(input.get("code").get("writeOnly").asBoolean());
        var output=schemas.get("PasswordResetVerificationResponse").get("properties");assertEquals(2,output.size());assertTrue(output.has("resetTicket"));assertTrue(output.has("expiresAt"));
        assertTrue(doc.get("paths").has(PATH));assertTrue(doc.get("paths").has(CONFIRM));
    }
}
