package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.example.music.user.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetConfirmationConcurrencyIntegrationTest extends PasswordResetConfirmationIntegrationSupport {
    @Test void should_consume_and_update_once_for_six_concurrent_ticket_requests() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var issued=issue(ready(user.getEmail()));
        try(var pool=Executors.newFixedThreadPool(6)) {
            var start=new CountDownLatch(1);
            var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++) results.add(pool.submit(()->{start.await();return confirm(issued,"new-password").getResponse().getStatus();}));
            start.countDown();
            int success=0;
            for(var result:results) {
                int status=result.get(30,TimeUnit.SECONDS);
                assertTrue(status==204||status==400);
                if(status==204) success++;
            }
            assertEquals(1,success);
        }
        assertEquals(1L,((Number)row(user).get("token_version")).longValue());
        verify(updater,times(1)).update(any(),anyString());
        verify(users,times(1)).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void should_preserve_new_ticket_when_new_issuance_precedes_old_consumption() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var old=issue(ready(user.getEmail()));
        var fresh=new AtomicReference<Issued>();
        doAnswer(invocation->{
            cooldown(user.getEmail());
            fresh.set(issue(ready(user.getEmail())));
            return invocation.callRealMethod();
        }).when(tickets).consume(any());
        assertProblem(confirm(old,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertFalse(ticketState(fresh.get()).isEmpty());
        verify(updater,never()).update(any(),anyString());
    }

    @Test void should_finish_prior_consumption_and_invalidate_new_old_version_ticket() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var old=issue(ready(user.getEmail()));
        var fresh=new AtomicReference<Issued>();
        doAnswer(invocation->{
            cooldown(user.getEmail());
            fresh.set(issue(ready(user.getEmail())));
            return invocation.callRealMethod();
        }).when(updater).update(any(),anyString());
        assertEquals(204,confirm(old,"new-password").getResponse().getStatus());
        assertEquals("0",ticketState(fresh.get()).get("tokenVersion"));
        doCallRealMethod().when(updater).update(any(),anyString());
        assertProblem(confirm(fresh.get(),"another-pass"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertFalse(ticketState(fresh.get()).isEmpty());
    }

    @Test void should_preserve_pending_mail_during_ticket_consumption() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var issued=issue(ready(user.getEmail()));
        cooldown(user.getEmail());
        var pending=accept(user.getEmail());
        assertEquals(204,confirm(issued,"new-password").getResponse().getStatus());
        assertEquals(pending.digest(),redis.opsForValue().get(P+"pending:"+digest(user.getEmail())));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(P+"task:"+pending.digest())));
    }

    @ParameterizedTest @ValueSource(strings={"email","role","hash","version","delete"})
    void should_reject_changed_prepared_snapshot_after_consumption(String change) throws Exception {
        var user=save("a@example.com",UserRole.ADMIN);
        var issued=issue(ready(user.getEmail()));
        doAnswer(invocation->{
            var consumed=invocation.callRealMethod();
            switch(change) {
                case "email" -> jdbc.update("update users set email='A@example.com' where id=?",user.getId());
                case "role" -> jdbc.update("update users set user_role='USER' where id=?",user.getId());
                case "hash" -> jdbc.update("update users set password_token=? where id=?",hasher.hash("concurrent-password"),user.getId());
                case "version" -> jdbc.update("update users set token_version=1 where id=?",user.getId());
                case "delete" -> jdbc.update("delete from users where id=?",user.getId());
            }
            return consumed;
        }).when(tickets).consume(any());
        assertProblem(confirm(issued,"new-password"),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        assertTrue(ticketState(issued).isEmpty());
        verify(updater,times(1)).update(any(),anyString());
        verify(users,times(1)).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void should_preserve_concurrent_username_after_preparation() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var issued=issue(ready(user.getEmail()));
        doAnswer(invocation->{
            var consumed=invocation.callRealMethod();
            jdbc.update("update users set username='renamed' where id=?",user.getId());
            return consumed;
        }).when(tickets).consume(any());
        assertEquals(204,confirm(issued,"new-password").getResponse().getStatus());
        assertEquals("renamed",row(user).get("username"));
    }

    @Test void should_interleave_requests_without_consuming_twice() throws Exception {
        var user=save("a@example.com",UserRole.USER);
        var issued=issue(ready(user.getEmail()));
        var atConsume=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var first=new AtomicBoolean(true);
        doAnswer(invocation->{
            if(first.compareAndSet(true,false)) {
                atConsume.countDown();
                assertTrue(release.await(15,TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(tickets).consume(any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var pending=pool.submit(()->confirm(issued,"first-password"));
            try {
                assertTrue(atConsume.await(15,TimeUnit.SECONDS));
                assertEquals(204,confirm(issued,"second-password").getResponse().getStatus());
            } finally { release.countDown(); }
            assertProblem(pending.get(15,TimeUnit.SECONDS),400,"PASSWORD_RESET_VERIFICATION_FAILED");
        }
        verify(updater,times(1)).update(any(),anyString());
    }
    @ParameterizedTest @ValueSource(strings = {" Legacy-abc ", "Legacy-AbC"})
    void should_conflict_when_legacy_hash_changes_after_consumption(String changed) throws Exception {
        var user = save("a@example.com", UserRole.USER);
        String original = " Legacy-AbC ";
        jdbc.update("update users set password_token=? where id=?", original, user.getId());
        var issued = issue(ready(user.getEmail()));
        doAnswer(invocation -> {
            var consumed = invocation.callRealMethod();
            assertEquals(original, row(user).get("password_token"));
            jdbc.update("update users set password_token=? where id=?", changed, user.getId());
            return consumed;
        }).when(tickets).consume(any());
        assertProblem(confirm(issued, "new-password"), 400, "PASSWORD_RESET_VERIFICATION_FAILED");
        assertTrue(ticketState(issued).isEmpty());
        assertEquals(changed, row(user).get("password_token"));
        assertEquals(0L, ((Number) row(user).get("token_version")).longValue());
        var expected = org.mockito.ArgumentCaptor.forClass(PasswordResetPreparedSnapshot.class);
        verify(updater).update(expected.capture(), anyString());
        assertEquals(original, expected.getValue().passwordHash());
        verify(users, times(1)).resetPasswordIfSnapshotMatches(eq(user.getId()), eq(user.getEmail()), eq("USER"),
                eq(original), eq(0L), anyString());
    }

}
