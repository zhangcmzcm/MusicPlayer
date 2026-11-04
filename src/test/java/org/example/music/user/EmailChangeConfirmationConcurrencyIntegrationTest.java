package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class EmailChangeConfirmationConcurrencyIntegrationTest extends EmailChangeConfirmationIntegrationSupport {
    @Test void should_consume_and_update_exactly_once_for_parallel_correct_requests() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var consumed=new AtomicInteger();
        doAnswer(i->{i.callRealMethod();consumed.incrementAndGet();return null;}).when(confirmations).consume(any());
        var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);
        try{List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<6;i++)results.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return confirm(value,c).getResponse().getStatus();}));start.countDown();int success=0;for(var result:results){int status=result.get(15,TimeUnit.SECONDS);assertTrue(Set.of(204,400,401).contains(status));if(status==204)success++;}assertEquals(1,success);assertEquals(1,consumed.get());assertEquals(1L,((Number)row(user).get("token_version")).longValue());}
        finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_reject_old_confirmation_if_new_application_activates_first() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var old=send(value,"first@example.com");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(i->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return i.callRealMethod();}).when(hasher).matches(eq(old.code()),anyString());
        try{var result=pool.submit(()->confirm(value,old));assertTrue(entered.await(5,TimeUnit.SECONDS));clearSendCooldown(user);var next=send(value,"second@example.com");var current=active(user);release.countDown();assertCode(result.get(15,TimeUnit.SECONDS),400,"EMAIL_CHANGE_VERIFICATION_FAILED");assertEquals(current,active(user));assertEquals("old@example.com",row(user).get("email"));assertEquals(204,confirm(value,next).getResponse().getStatus());}
        finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_finish_consumed_confirmation_without_deleting_new_active() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var old=send(value,"first@example.com");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(i->{i.callRealMethod();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return null;}).when(confirmations).consume(any());
        try{var result=pool.submit(()->confirm(value,old));assertTrue(entered.await(5,TimeUnit.SECONDS));clearSendCooldown(user);var next=send(value,"second@example.com");var current=active(user);release.countDown();assertEquals(204,result.get(15,TimeUnit.SECONDS).getResponse().getStatus());assertEquals("first@example.com",row(user).get("email"));assertEquals(current,active(user));assertCode(confirm(token(user,UserRole.USER,0L),next),400,"EMAIL_CHANGE_VERIFICATION_FAILED");assertEquals(current,active(user));}
        finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_reject_correct_code_if_fifth_wrong_code_wins() throws Exception {
        var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");for(int i=0;i<4;i++)assertCode(confirm(value,c,wrongCode(c)),400,"EMAIL_CHANGE_VERIFICATION_FAILED");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(i->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return i.callRealMethod();}).when(hasher).matches(eq(c.code()),anyString());
        try{var correct=pool.submit(()->confirm(value,c));assertTrue(entered.await(5,TimeUnit.SECONDS));assertCode(confirm(value,c,wrongCode(c)),400,"EMAIL_CHANGE_VERIFICATION_FAILED");release.countDown();assertCode(correct.get(15,TimeUnit.SECONDS),400,"EMAIL_CHANGE_VERIFICATION_FAILED");assertTrue(active(user).isEmpty());assertNull(row(user).get("email"));}
        finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @ParameterizedTest @ValueSource(strings={"email","version","role","delete","username"})
    void should_preserve_concurrent_state_after_consumption(String change) throws Exception {
        var user=save(UserRole.ADMIN,"old@example.com");String value=token(user,UserRole.ADMIN,0L);var c=send(value,"new@example.com");
        doAnswer(i->{i.callRealMethod();switch(change){case "email"->jdbc.update("update users set email='OLD@example.com' where id=?",user.getId());case "version"->jdbc.update("update users set token_version=1 where id=?",user.getId());case "role"->jdbc.update("update users set user_role='USER' where id=?",user.getId());case "delete"->jdbc.update("delete from users where id=?",user.getId());case "username"->jdbc.update("update users set username='renamed' where id=?",user.getId());}return null;}).when(confirmations).consume(any());
        var result=confirm(value,c);if(change.equals("username")){assertEquals(204,result.getResponse().getStatus());assertEquals("renamed",row(user).get("username"));}else assertCode(result,change.equals("email")?409:401,change.equals("email")?"EMAIL_CHANGE_CONFLICT":"TOKEN_REVOKED");assertTrue(active(user).isEmpty());
    }
}
