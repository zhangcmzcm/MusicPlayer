package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.example.music.user.domain.*;
import org.junit.jupiter.api.Test;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetConfirmationConcurrencyIntegrationTest extends PasswordResetConfirmationIntegrationSupport {
    @Test void should_consume_and_update_at_most_once_for_six_concurrent_correct_requests() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1); var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++) results.add(pool.submit(()->{start.await();return confirm(ready,"new-password").getResponse().getStatus();}));
            start.countDown(); int success=0;
            for(var result:results) { int status=result.get(30,TimeUnit.SECONDS); assertTrue(status==204||status==400); if(status==204)success++; }
            assertEquals(1,success);
        }
        assertEquals(1L,((Number)row(user).get("token_version")).longValue()); verify(updater,times(1)).update(any(),anyString());
    }
    @Test void should_reject_old_consume_if_new_activation_wins() throws Exception {
        var user=save("a@example.com",UserRole.USER); var old=ready(user.getEmail()); var fresh=new AtomicReference<Ready>();
        doAnswer(i->{cooldown(user.getEmail()); fresh.set(ready(user.getEmail()));return i.callRealMethod();}).when(confirmations).consume(any());
        assertProblem(confirm(old,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertEquals(fresh.get().accepted().digest(),active(user.getEmail())); verify(updater,never()).update(any(),anyString());
        assertEquals("0",activeState(fresh.get().accepted()).get("attempts"));
    }
    @Test void should_finish_old_sql_and_keep_new_active_when_old_consume_wins() throws Exception {
        var user=save("a@example.com",UserRole.USER); var old=ready(user.getEmail()); var fresh=new AtomicReference<Ready>();
        doAnswer(i->{cooldown(user.getEmail()); fresh.set(ready(user.getEmail()));return i.callRealMethod();}).when(updater).update(any(),anyString());
        assertEquals(204,confirm(old,"new-password").getResponse().getStatus());
        assertEquals(fresh.get().accepted().digest(),active(user.getEmail())); assertEquals("0",activeState(fresh.get().accepted()).get("tokenVersion"));
        doCallRealMethod().when(updater).update(any(),anyString());
        assertProblem(confirm(fresh.get(),"another-pass"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertEquals(fresh.get().accepted().digest(),active(user.getEmail()));
    }
    @Test void should_allow_old_active_while_new_request_is_only_pending() throws Exception {
        var user=save("a@example.com",UserRole.USER); var old=ready(user.getEmail()); cooldown(user.getEmail()); var pending=accept(user.getEmail());
        assertEquals(old.accepted().digest(),active(user.getEmail())); assertEquals(204,confirm(old,"new-password").getResponse().getStatus());
        assertEquals(pending.digest(),redis.opsForValue().get(P+"pending:"+digest(user.getEmail())));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(P+"task:"+pending.digest())));
    }
    @Test void should_reject_correct_consume_after_fifth_wrong_wins() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        doAnswer(i->{var snapshot=i.getArgument(0,PasswordResetChallengeSnapshot.class);for(int n=0;n<5;n++) confirmations.recordFailure(snapshot);return i.callRealMethod();}).when(confirmations).consume(any());
        assertProblem(confirm(ready,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED"); verify(updater,never()).update(any(),anyString());
        assertTrue(activeState(ready.accepted()).isEmpty());
    }
    @Test void should_allow_correct_consume_after_nonterminal_wrong_wins() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        doAnswer(i->{confirmations.recordFailure(i.getArgument(0));return i.callRealMethod();}).when(confirmations).consume(any());
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus());
    }
    @Test void should_conflict_on_prepared_email_change_but_preserve_concurrent_username() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        doAnswer(i->{var consumed=i.callRealMethod();jdbc.update("update users set email='A@example.com' where id=?",user.getId());return consumed;}).when(confirmations).consume(any());
        assertProblem(confirm(ready,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED"); assertTrue(activeState(ready.accepted()).isEmpty());
        assertEquals(0L,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_preserve_concurrent_username_after_preparation() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        doAnswer(i->{var consumed=i.callRealMethod();jdbc.update("update users set username='renamed' where id=?",user.getId());return consumed;}).when(confirmations).consume(any());
        assertEquals(204,confirm(ready,"new-password").getResponse().getStatus()); assertEquals("renamed",row(user).get("username"));
    }
    @Test void should_interleave_two_requests_with_latches_without_consuming_twice() throws Exception {
        var user=save("a@example.com",UserRole.USER); var ready=ready(user.getEmail());
        var atConsume=new CountDownLatch(1); var release=new CountDownLatch(1); var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{if(first.compareAndSet(true,false)){atConsume.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}return i.callRealMethod();}).when(confirmations).consume(any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->confirm(ready,"first-password"));
            try { assertTrue(atConsume.await(15,TimeUnit.SECONDS)); assertEquals(204,confirm(ready,"second-password").getResponse().getStatus()); }
            finally { release.countDown(); }
            assertProblem(pending.get(15,TimeUnit.SECONDS),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        }
        verify(updater,times(1)).update(any(),anyString());
    }
}
