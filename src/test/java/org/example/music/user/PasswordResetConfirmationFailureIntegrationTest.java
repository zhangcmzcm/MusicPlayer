package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.*;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetConfirmationFailureIntegrationTest extends PasswordResetConfirmationIntegrationSupport {
    @ParameterizedTest @ValueSource(strings={"admit","account","read","consume","unexecuted"})
    void should_fail_closed_after_real_redis_operation_loses_reply(String operation) throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=issue(ready(user.getEmail())); var before=row(user);
        clearInvocations(transactions);
        switch(operation) {
            case "admit" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(quota).admitIp(anyString());
            case "account" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(quota).admitAccount(anyInt());
            case "read" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetVerificationUnavailableException(null);}).when(tickets).readByDigest(anyString());
            case "unexecuted" -> doThrow(new PasswordResetVerificationUnavailableException(null)).when(tickets).consume(any());
            case "consume" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetVerificationUnavailableException(null);}).when(tickets).consume(any());
        }
        var input=ready;
        assertProblem(confirm(input,"new-password"),503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        assertEquals(before,row(user)); verify(updater,never()).update(any(),anyString());
        verify(users,never()).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
        assertEquals("1",redis.opsForHash().get("music:auth:password-reset-completion:ip:"+digest("127.0.0.1"),"count"));
        if(operation.equals("consume") || operation.equals("unexecuted")) {
            var definitions=org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
            verify(transactions,atLeastOnce()).getTransaction(definitions.capture());
            assertTrue(definitions.getAllValues().stream().allMatch(TransactionDefinition::isReadOnly));
        }
        if(operation.equals("consume")) { assertTrue(ticketState(ready).isEmpty()); verify(tickets,times(1)).consume(any()); }
        else assertFalse(ticketState(ready).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"begin","execute","rollback","committed"})
    void should_never_restore_after_consumption_and_database_failure(String phase) throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=issue(ready(user.getEmail())); var before=row(user);
        doAnswer(i->{
            var consumed=i.callRealMethod();
            if(phase.equals("begin")) doThrow(new CannotCreateTransactionException("secret")).when(transactions).getTransaction(any());
            else if(phase.equals("execute")) doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret")).when(users).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
            else if(phase.equals("rollback")) doAnswer(c->{transactions.rollback(c.getArgument(0));throw new TransactionSystemException("secret");}).when(transactions).commit(any());
            else doAnswer(c->{c.callRealMethod();throw new TransactionSystemException("secret");}).when(transactions).commit(any());
            return consumed;
        }).when(tickets).consume(any());
        assertProblem(confirm(ready,"new-password"),503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        doCallRealMethod().when(transactions).getTransaction(any()); doCallRealMethod().when(transactions).commit(any());
        assertTrue(ticketState(ready).isEmpty());
        if(phase.equals("committed")) {
            assertTrue(hasher.matches("new-password",(String)row(user).get("password_token"))); assertEquals(1L,((Number)row(user).get("token_version")).longValue());
            var login=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                    .contentType("application/json").content(json.writeValueAsString(Map.of("loginType","USERNAME_PASSWORD","account",user.getUsername(),"password","new-password")))).andReturn();
            assertEquals(200,login.getResponse().getStatus(),login.getResponse().getContentAsString());
        } else assertEquals(before,row(user));
        verify(updater,times(1)).update(any(),anyString());
        verify(tickets,times(1)).consume(any());
        verify(users,times(phase.equals("begin")?0:1)).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
        assertProblem(confirm(ready,"other-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
    }
    @Test void should_keep_ticket_and_row_when_password_hash_computation_fails() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=issue(ready(user.getEmail())); var state=ticketState(ready); var before=row(user);
        doThrow(new IllegalStateException("private-password")).when(hasher).hash("new-password");
        var result=confirm(ready,"new-password"); assertProblem(result,503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        assertFalse(result.getResponse().getContentAsString().contains("private-password")); assertEquals(state,ticketState(ready)); assertEquals(before,row(user));
        verify(tickets,never()).consume(any());
    }
    Issued nearExpiry(UserEntity user) throws Exception {
        var issued=issue(ready(user.getEmail()));
        long now=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
        long end=now+2000;String key=org.example.music.user.repository.PasswordResetTicketStore.PREFIX+"ticket:"+digest(issued.resetTicket());
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "redis.call('HSET',KEYS[1],'issuedAt',ARGV[1],'expiresAt',ARGV[2]);redis.call('PEXPIREAT',KEYS[1],ARGV[2]);redis.call('PEXPIREAT',KEYS[2],ARGV[2]);return 1",Long.class),
                List.of(key,org.example.music.user.repository.PasswordResetTicketStore.PREFIX+"active:"+user.getId()),Long.toString(end-300000),Long.toString(end));
        return new Issued(issued.resetTicket(),Instant.ofEpochMilli(end));
    }
    void awaitExpiry(Instant ending) throws Exception {
        try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
            var elapsed=new CountDownLatch(1); scheduler.schedule(elapsed::countDown,Math.max(1,ending.toEpochMilli()-System.currentTimeMillis()+100),TimeUnit.MILLISECONDS);
            assertTrue(elapsed.await(5,TimeUnit.SECONDS));
        }
    }
    @Test void should_reject_expiry_after_hashing_but_before_consumption() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=nearExpiry(user); var before=row(user);
        doAnswer(i->{awaitExpiry(ready.expiresAt());return i.callRealMethod();}).when(tickets).consume(any());
        assertProblem(confirm(ready,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED"); assertEquals(before,row(user)); verify(updater,never()).update(any(),anyString());
    }
    @Test void should_allow_commit_after_deadline_when_consumed_in_time() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=nearExpiry(user);
        doAnswer(i->{awaitExpiry(ready.expiresAt());return i.callRealMethod();}).when(updater).update(any(),anyString());
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus()); assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_not_return_success_for_replay_after_ignored_success_response() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=issue(ready(user.getEmail()));
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus());
        assertProblem(confirm(ready,"other-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertTrue(hasher.matches("new-password",(String)row(user).get("password_token"))); verify(updater,times(1)).update(any(),anyString());
    }
    @ParameterizedTest @ValueSource(strings={"begin","query","commit"})
    void should_preserve_ticket_on_read_transaction_failure(String phase) throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var issued=issue(ready(user.getEmail()));
        var before=row(user);
        var state=ticketState(issued);
        long ending=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME',KEYS[1])",Long.class),
                List.of(org.example.music.user.repository.PasswordResetTicketStore.PREFIX+"ticket:"+digest(issued.resetTicket())));
        switch(phase) {
            case "begin" -> doThrow(new CannotCreateTransactionException("private-password")).when(transactions).getTransaction(any());
            case "query" -> jdbc.execute("RENAME TABLE users TO password_reset_unavailable_users");
            case "commit" -> doAnswer(invocation->{invocation.callRealMethod();throw new TransactionSystemException("private-password");})
                    .when(transactions).commit(any());
        }
        org.springframework.test.web.servlet.MvcResult result;
        try {
            result=confirm(issued,"new-password");
        } finally {
            if(phase.equals("query")) jdbc.execute("RENAME TABLE password_reset_unavailable_users TO users");
        }
        assertProblem(result,503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        assertFalse(result.getResponse().getContentAsString().contains("private-password"));
        doCallRealMethod().when(transactions).getTransaction(any());
        doCallRealMethod().when(transactions).commit(any());
        assertEquals(before,row(user));
        assertEquals(state,ticketState(issued));
        assertEquals(ending,redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "return redis.call('PEXPIRETIME',KEYS[1])",Long.class),
                List.of(org.example.music.user.repository.PasswordResetTicketStore.PREFIX+"ticket:"+digest(issued.resetTicket()))));
        verify(tickets,never()).consume(any());
        verify(updater,never()).update(any(),anyString());
    }
    @Test void should_preserve_ticket_when_current_password_hash_is_damaged() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var issued=issue(ready(user.getEmail()));
        jdbc.update("update users set password_token='broken' where id=?",user.getId());
        var state=ticketState(issued);
        clearInvocations(hasher);
        assertProblem(confirm(issued,"new-password"),503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        assertEquals(state,ticketState(issued));
        verifyNoInteractions(hasher,updater);
        verify(tickets,never()).consume(any());
    }

}
