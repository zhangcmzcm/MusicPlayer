package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.example.music.user.domain.*;
import org.junit.jupiter.api.Test;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetVerificationConcurrencyIntegrationTest extends PasswordResetVerificationIntegrationSupport {
    @Test void should_exchange_once_for_six_concurrent_correct_requests() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)results.add(pool.submit(()->{start.await();return verifyCode(live).getResponse().getStatus();}));
            start.countDown();int successes=0;
            for(var future:results) { int status=future.get(30,TimeUnit.SECONDS);assertTrue(status==200||status==400);if(status==200)successes++; }
            assertEquals(1,successes);
        }
        assertEquals(1,redis.keys(T+"ticket:*").size());assertEquals(0L,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_lose_old_exchange_when_new_code_activates_first() throws Exception {
        var user=save("a@example.com",UserRole.USER);var old=ready(user.getEmail());var fresh=new AtomicReference<Ready>();
        doAnswer(i->{cooldown(user.getEmail());fresh.set(ready(user.getEmail()));return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        assertProblem(verifyCode(old),400,"PASSWORD_RESET_VERIFICATION_FAILED");assertTrue(tickets.readCurrent(user.getId()).isEmpty());
        assertEquals(fresh.get().accepted().digest(),active(user.getEmail()));assertEquals("0",activeState(fresh.get().accepted()).get("attempts"));
    }
    @Test void should_reject_correct_exchange_if_fifth_wrong_wins() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        doAnswer(i->{for(int n=0;n<5;n++)confirmations.recordFailure(i.getArgument(0));return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        assertProblem(verifyCode(live),400,"PASSWORD_RESET_VERIFICATION_FAILED");assertTrue(tickets.readCurrent(user.getId()).isEmpty());assertTrue(activeState(live.accepted()).isEmpty());
    }
    @Test void should_allow_nonterminal_wrong_then_ignore_late_wrong_after_success() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());var snapshot=confirmations.read(live.accepted().digest()).orElseThrow();
        doAnswer(i->{confirmations.recordFailure(i.getArgument(0));return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        issuedTicket(verifyCode(live));var ticket=tickets.readCurrent(user.getId()).orElseThrow();confirmations.recordFailure(snapshot);
        assertEquals(ticket,tickets.readCurrent(user.getId()).orElseThrow());
    }
    @Test void should_preserve_username_changed_between_final_check_and_exchange() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());
        doAnswer(i->{jdbc.update("update users set username='renamed' where id=?",user.getId());return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        issuedTicket(verifyCode(live));assertEquals("renamed",row(user).get("username"));assertEquals(0,tickets.readCurrent(user.getId()).orElseThrow().account().tokenVersion());
    }
    @Test void should_reject_second_binding_change_without_extra_dummy_comparison() throws Exception {
        var user=save("a@example.com",UserRole.ADMIN);var live=ready(user.getEmail());var checks=new AtomicInteger();
        doAnswer(i->{if(checks.incrementAndGet()==2)jdbc.update("update users set user_role='USER' where id=?",user.getId());return i.callRealMethod();}).when(confirmationReader).isCurrent(any());
        clearInvocations(hasher);assertProblem(verifyCode(live),400,"PASSWORD_RESET_VERIFICATION_FAILED");verify(hasher,times(1)).matches(eq(live.code()),anyString());
        verify(tickets,never()).readCurrent(anyInt());verify(tickets,never()).exchange(any(),any(),anyString());assertEquals("0",activeState(live.accepted()).get("attempts"));
    }
    @Test void should_store_saved_old_binding_if_database_changes_after_final_check() throws Exception {
        var user=save("a@example.com",UserRole.ADMIN);var live=ready(user.getEmail());
        doAnswer(i->{jdbc.update("update users set email='changed@example.com',user_role='USER',token_version=1 where id=?",user.getId());return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        issuedTicket(verifyCode(live));var ticket=tickets.readCurrent(user.getId()).orElseThrow();assertEquals("a@example.com",ticket.account().email());
        assertEquals(UserRole.ADMIN,ticket.account().userRole());assertEquals(0L,ticket.account().tokenVersion());assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_keep_newer_version_ticket_when_old_proof_returns_late() throws Exception {
        var user=save("a@example.com",UserRole.USER);var old=ready(user.getEmail());
        jdbc.update("update users set token_version=1 where id=?",user.getId());cooldown(user.getEmail());var newer=ready(user.getEmail());issuedTicket(verifyCode(newer));
        var high=tickets.readCurrent(user.getId()).orElseThrow();
        // Restore only the old protocol fixture to model an earlier proof's delayed exchange.
        var lowSnapshot=new PasswordResetAccountSnapshot(user.getId(),user.getEmail(),UserRole.USER,0);
        var admission=store.admit(digest("other@example.com"),PasswordResetIdentifiers.generate());
        var task=new PasswordResetTask(digest(old.accepted().id()),digest(user.getEmail()),user.getEmail(),admission,Optional.of(lowSnapshot),"late");
        store.enqueue(task,1000);var claim=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();assertTrue(store.prepare(claim,hasher.hash(old.code())));assertTrue(store.activate(claim));store.finish(claim);
        var saved=confirmations.read(task.challengeDigest()).orElseThrow();
        assertTrue(tickets.exchange(saved,Optional.of(high),PasswordResetIdentifiers.generate()).isEmpty());assertEquals(high,tickets.readCurrent(user.getId()).orElseThrow());
    }
    @Test void should_control_consume_race_with_latches() throws Exception {
        var user=save("a@example.com",UserRole.USER);var live=ready(user.getEmail());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(i->{if(first.compareAndSet(true,false)){entered.countDown();assertTrue(release.await(15,TimeUnit.SECONDS));}return i.callRealMethod();}).when(tickets).exchange(any(),any(),anyString());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var waiting=pool.submit(()->verifyCode(live));
            try { assertTrue(entered.await(15,TimeUnit.SECONDS));issuedTicket(verifyCode(live)); }
            finally { release.countDown(); }
            assertProblem(waiting.get(15,TimeUnit.SECONDS),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        }
        assertEquals(1,redis.keys(T+"ticket:*").size());
    }
}
