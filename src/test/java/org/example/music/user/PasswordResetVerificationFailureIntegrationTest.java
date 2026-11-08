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
class PasswordResetVerificationFailureIntegrationTest extends PasswordResetVerificationIntegrationSupport {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void should_keep_actual_execution_effects_after_unknown_exchange(boolean executed) throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());var before=row(user);
        doAnswer(i->{if(executed)i.callRealMethod();throw new PasswordResetVerificationUnavailableException(null);}).when(tickets).exchange(any(),any(),anyString());
        assertProblem(verifyCode(live),503,"PASSWORD_RESET_VERIFICATION_UNAVAILABLE");assertEquals(before,row(user));verify(tickets,times(1)).exchange(any(),any(),anyString());
        assertEquals(executed,tickets.readCurrent(user.getId()).isPresent());assertEquals(!executed,!activeState(live.accepted()).isEmpty());
        doCallRealMethod().when(tickets).exchange(any(),any(),anyString());
        if(executed)assertProblem(verifyCode(live),400,"PASSWORD_RESET_VERIFICATION_FAILED");else issuedTicket(verifyCode(live));
        verify(users,never()).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
    }
    @ParameterizedTest @ValueSource(strings={"ip","account","fail"})
    void should_not_refund_or_retry_when_real_admission_or_failure_loses_reply(String operation) throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        switch(operation) {
            case "ip" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).admitIp(anyString());
            case "account" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).admitAccount(anyInt());
            case "fail" -> doAnswer(i->{i.callRealMethod();throw new PasswordResetConfirmationUnavailableException(null);}).when(confirmations).recordFailure(any());
        }
        var input=operation.equals("fail")?new Ready(live.accepted(),live.code().equals("000000")?"000001":"000000"):live;
        assertProblem(verifyCode(input),503,"PASSWORD_RESET_VERIFICATION_UNAVAILABLE");
        assertEquals("1",redis.opsForHash().get("music:auth:password-reset-confirmation:ip:"+digest("127.0.0.1"),"count"));
        if(operation.equals("fail"))assertEquals("1",activeState(live.accepted()).get("attempts"));verify(tickets,never()).exchange(any(),any(),anyString());
    }
    @ParameterizedTest @ValueSource(strings={"begin","query","commit"})
    void should_convert_real_readonly_database_fault_without_consuming_code(String phase) throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        switch(phase) {
            case "begin" -> doThrow(new CannotCreateTransactionException("private")).when(transactions).getTransaction(any());
            case "query" -> doThrow(new org.springframework.dao.DataAccessResourceFailureException("private")).when(users).findPasswordResetSnapshotById(anyInt());
            case "commit" -> doAnswer(i->{i.callRealMethod();throw new TransactionSystemException("private");}).when(transactions).commit(any());
        }
        var result=verifyCode(live);assertProblem(result,503,"PASSWORD_RESET_VERIFICATION_UNAVAILABLE");assertFalse(result.getResponse().getContentAsString().contains("private"));
        assertEquals("0",activeState(live.accepted()).get("attempts"));verify(tickets,never()).exchange(any(),any(),anyString());
    }
    @Test void should_preserve_code_and_ticket_when_old_record_is_damaged() throws Exception {
        var user=save("a@example.com",UserRole.USER);issuedTicket(verifyCode(ready(user.getEmail())));var old=tickets.readCurrent(user.getId()).orElseThrow();cooldown(user.getEmail());var live=ready(user.getEmail());
        redis.opsForHash().put(T+"ticket:"+old.digest(),"extra","bad");var before=redis.opsForHash().entries(T+"ticket:"+old.digest());
        assertProblem(verifyCode(live),503,"PASSWORD_RESET_VERIFICATION_UNAVAILABLE");assertEquals(before,redis.opsForHash().entries(T+"ticket:"+old.digest()));
        assertEquals("0",activeState(live.accepted()).get("attempts"));assertEquals(old.digest(),redis.opsForValue().get(T+"active:"+user.getId()));
    }
    @Test void should_reject_collision_before_consuming_code_or_replacing_ticket() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        doAnswer(i->{redis.opsForValue().set(T+"ticket:"+i.getArgument(2,String.class),"occupied");return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        assertProblem(verifyCode(live),503,"PASSWORD_RESET_VERIFICATION_UNAVAILABLE");assertTrue(tickets.readCurrent(user.getId()).isEmpty());assertEquals("0",activeState(live.accepted()).get("attempts"));
    }
    Ready nearExpiry(UserEntity user) {
        long now=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
        var end=Instant.ofEpochMilli(now+2000);String id=PasswordResetIdentifiers.generate();
        var task=new PasswordResetTask(digest(id),digest(user.getEmail()),user.getEmail(),new PasswordResetAdmission(end.minusSeconds(300),end),Optional.of(reader.capture(user.getEmail()).orElseThrow()),"expiry");
        store.enqueue(task,1000);assertTrue(processor.processNext());return new Ready(new Accepted(id,end),sent.getLast().code());
    }
    @Test void should_reject_expiry_between_comparison_and_exchange() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=nearExpiry(user);
        doAnswer(i->{
            try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
                var elapsed=new CountDownLatch(1);scheduler.schedule(elapsed::countDown,Math.max(1,live.accepted().expiresAt().toEpochMilli()-System.currentTimeMillis()+100),TimeUnit.MILLISECONDS);
                assertTrue(elapsed.await(5,TimeUnit.SECONDS));
            }
            return i.callRealMethod();
        }).when(tickets).exchange(any(),any(),anyString());
        assertProblem(verifyCode(live),400,"PASSWORD_RESET_VERIFICATION_FAILED");assertTrue(tickets.readCurrent(user.getId()).isEmpty());
    }
    @Test void should_grant_independent_300_seconds_when_code_is_near_expiry() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=nearExpiry(user);var result=verifyCode(live);issuedTicket(result);
        var ticket=tickets.readCurrent(user.getId()).orElseThrow();assertEquals(300000,ticket.period().expiresAt().toEpochMilli()-ticket.period().issuedAt().toEpochMilli());
        assertTrue(ticket.period().expiresAt().isAfter(live.accepted().expiresAt().plusSeconds(290)));
    }
    @Test void should_not_replay_success_after_response_is_ignored() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());issuedTicket(verifyCode(live));var old=tickets.readCurrent(user.getId()).orElseThrow();
        assertProblem(verifyCode(live),400,"PASSWORD_RESET_VERIFICATION_FAILED");assertEquals(old,tickets.readCurrent(user.getId()).orElseThrow());verify(tickets,times(1)).exchange(any(),any(),anyString());
    }
    @Test void should_keep_code_when_second_actual_readonly_commit_fails() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());var commits=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(i->{var result=i.callRealMethod();if(commits.incrementAndGet()==2)throw new TransactionSystemException("private");return result;}).when(transactions).commit(any());
        clearInvocations(hasher);assertProblem(verifyCode(live),503,"PASSWORD_RESET_VERIFICATION_UNAVAILABLE");
        verify(hasher,times(1)).matches(eq(live.code()),anyString());verify(tickets,never()).readCurrent(anyInt());verify(tickets,never()).exchange(any(),any(),anyString());
        assertEquals("0",activeState(live.accepted()).get("attempts"));
    }

}
