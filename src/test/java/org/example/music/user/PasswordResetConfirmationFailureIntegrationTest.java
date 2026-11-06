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
    @ParameterizedTest @ValueSource(strings={"admit","account","fail","consume"})
    void should_fail_closed_after_real_redis_operation_loses_reply(String operation) throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail()); var before=row(user);
        switch(operation) {
            case "admit" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).admitIp(anyString());
            case "account" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).admitAccount(anyInt());
            case "fail" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).recordFailure(any());
            case "consume" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).consume(any());
        }
        var input=operation.equals("fail")?new Ready(ready.accepted(),ready.code().equals("000000")?"000001":"000000"):ready;
        assertProblem(confirm(input,"new-password"),503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        assertEquals(before,row(user)); verify(updater,never()).update(any(),anyString());
        verify(users,never()).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
        assertEquals("1",redis.opsForHash().get("music:auth:password-reset-confirmation:ip:"+digest("127.0.0.1"),"count"));
        if(operation.equals("consume")) { assertTrue(activeState(ready.accepted()).isEmpty()); verify(confirmations,times(1)).consume(any()); }
        else if(operation.equals("fail")) assertEquals("1",activeState(ready.accepted()).get("attempts"));
    }
    @ParameterizedTest @ValueSource(strings={"begin","execute","rollback","committed"})
    void should_never_restore_after_consumption_and_database_failure(String phase) throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail()); var before=row(user);
        doAnswer(i->{
            var consumed=i.callRealMethod();
            if(phase.equals("begin")) doThrow(new CannotCreateTransactionException("secret")).when(transactions).getTransaction(any());
            else if(phase.equals("execute")) doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret")).when(users).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
            else if(phase.equals("rollback")) doAnswer(c->{transactions.rollback(c.getArgument(0));throw new TransactionSystemException("secret");}).when(transactions).commit(any());
            else doAnswer(c->{c.callRealMethod();throw new TransactionSystemException("secret");}).when(transactions).commit(any());
            return consumed;
        }).when(confirmations).consume(any());
        assertProblem(confirm(ready,"new-password"),503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        doCallRealMethod().when(transactions).getTransaction(any()); doCallRealMethod().when(transactions).commit(any());
        assertTrue(activeState(ready.accepted()).isEmpty());
        if(phase.equals("committed")) {
            assertTrue(hasher.matches("new-password",(String)row(user).get("password_token"))); assertEquals(1L,((Number)row(user).get("token_version")).longValue());
        } else assertEquals(before,row(user));
        verify(updater,times(1)).update(any(),anyString());
        verify(confirmations,times(1)).consume(any());
        verify(users,times(phase.equals("begin")?0:1)).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
        assertProblem(confirm(ready,"other-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
    }
    @Test void should_keep_code_and_row_when_password_hash_computation_fails() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail()); var state=activeState(ready.accepted()); var before=row(user);
        doThrow(new IllegalStateException("private-password")).when(hasher).hash("new-password");
        var result=confirm(ready,"new-password"); assertProblem(result,503,"PASSWORD_RESET_CONFIRMATION_UNAVAILABLE");
        assertFalse(result.getResponse().getContentAsString().contains("private-password")); assertEquals(state,activeState(ready.accepted())); assertEquals(before,row(user));
        verify(confirmations,never()).consume(any());
    }
    Ready nearExpiry(UserEntity user) {
        long now=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
        var end=Instant.ofEpochMilli(now+2000); String id=PasswordResetIdentifiers.generate();
        var task=new PasswordResetTask(digest(id),digest(user.getEmail()),user.getEmail(),new PasswordResetAdmission(end.minusSeconds(300),end),Optional.of(reader.capture(user.getEmail()).orElseThrow()),"expiry");
        store.enqueue(task,1000); assertTrue(processor.processNext()); assertEquals(task.challengeDigest(),active(user.getEmail()));
        return new Ready(new Accepted(id,end),sent.getLast().code());
    }
    void awaitExpiry(Instant ending) throws Exception {
        try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
            var elapsed=new CountDownLatch(1); scheduler.schedule(elapsed::countDown,Math.max(1,ending.toEpochMilli()-System.currentTimeMillis()+100),TimeUnit.MILLISECONDS);
            assertTrue(elapsed.await(5,TimeUnit.SECONDS));
        }
    }
    @Test void should_reject_expiry_after_hashing_but_before_consumption() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=nearExpiry(user); var before=row(user);
        doAnswer(i->{awaitExpiry(ready.accepted().expiresAt());return i.callRealMethod();}).when(confirmations).consume(any());
        assertProblem(confirm(ready,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED"); assertEquals(before,row(user)); verify(updater,never()).update(any(),anyString());
    }
    @Test void should_allow_commit_after_deadline_when_consumed_in_time() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=nearExpiry(user);
        doAnswer(i->{awaitExpiry(ready.accepted().expiresAt());return i.callRealMethod();}).when(updater).update(any(),anyString());
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus()); assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_not_return_success_for_replay_after_ignored_success_response() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus());
        assertProblem(confirm(ready,"other-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertTrue(hasher.matches("new-password",(String)row(user).get("password_token"))); verify(updater,times(1)).update(any(),anyString());
    }
}
