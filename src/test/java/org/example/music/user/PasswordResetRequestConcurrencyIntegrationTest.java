package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.domain.*;
import org.example.music.user.email.PasswordResetMessage;
import org.example.music.user.service.PasswordResetWorkerLifecycle;
import org.example.music.user.config.PasswordResetProperties;
import org.junit.jupiter.api.Test;
@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetRequestConcurrencyIntegrationTest extends PasswordResetRequestIntegrationSupport {
 @Test void should_not_overwrite_newer_finished_request_when_old_capture_returns_late()throws Exception{
  save("alice@example.com",UserRole.USER);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  doAnswer(i->{var result=i.callRealMethod();if(calls.incrementAndGet()==1){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return result;}).when(reader).capture("alice@example.com");
  var pool=Executors.newSingleThreadExecutor();
  try{var old=pool.submit(()->request("alice@example.com"));assertTrue(entered.await(5,TimeUnit.SECONDS));cooldown("alice@example.com");var newer=accept("alice@example.com");assertTrue(processor.processNext());release.countDown();assertEquals(503,old.get(10,TimeUnit.SECONDS).getResponse().getStatus());assertEquals(newer.digest(),active("alice@example.com"));}
  finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
 }
 @Test void should_keep_claimed_replaced_task_counted_until_it_finishes_without_touching_new_active()throws Exception{
  save("alice@example.com",UserRole.USER);var old=accept("alice@example.com");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  doAnswer(i->{PasswordResetMessage m=i.getArgument(0);sent.add(m);if(calls.incrementAndGet()==1){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return null;}).when(mail).send(any());
  var pool=Executors.newSingleThreadExecutor();
  try{var running=pool.submit(processor::processNext);assertTrue(entered.await(5,TimeUnit.SECONDS));cooldown("alice@example.com");var newer=accept("alice@example.com");assertEquals(2L,redis.opsForZSet().zCard(P+"outstanding"));assertTrue(processor.processNext());assertEquals(newer.digest(),active("alice@example.com"));release.countDown();assertTrue(running.get(10,TimeUnit.SECONDS));assertEquals(newer.digest(),active("alice@example.com"));assertTrue(activeState(old).isEmpty());assertEquals(0L,redis.opsForZSet().zCard(P+"outstanding"));}
  finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
 }
 @Test void should_stop_activation_when_credentials_change_during_send()throws Exception{
  var user=save("alice@example.com",UserRole.USER);var a=accept("alice@example.com");doAnswer(i->{sent.add(i.getArgument(0));jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());return null;}).when(mail).send(any());
  assertTrue(processor.processNext());assertEquals(1,sent.size());assertNull(active("alice@example.com"));assertTrue(activeState(a).isEmpty());
 }
 @Test void should_preserve_old_binding_if_version_changes_after_last_db_check()throws Exception{
  var user=save("alice@example.com",UserRole.USER);var a=accept("alice@example.com");var count=new AtomicInteger();
  doAnswer(i->{var result=i.callRealMethod();if(count.incrementAndGet()==2)jdbc.update("update users set token_version=token_version+1 where id=?",user.getId());return result;}).when(reader).stillMatches(any(),anyString());
  processor.processNext();assertEquals(a.digest(),active("alice@example.com"));assertEquals("0",activeState(a).get("tokenVersion"));assertEquals(1L,((Number)row(user).get("token_version")).longValue());
 }
 @Test void should_claim_once_with_two_real_worker_lifecycles()throws Exception{
  save("alice@example.com",UserRole.USER);var a=accept("alice@example.com");var activated=new CountDownLatch(1);
  doAnswer(i->{var result=i.callRealMethod();activated.countDown();return result;}).when(store).activate(any());var properties=new PasswordResetProperties();properties.setWorkerConcurrency(1);
  var first=new PasswordResetWorkerLifecycle(processor,store,properties);var second=new PasswordResetWorkerLifecycle(processor,store,properties);
  try{first.start();second.start();assertTrue(activated.await(10,TimeUnit.SECONDS));}finally{first.stop();second.stop();}
  verify(mail,times(1)).send(any());assertEquals(a.digest(),active("alice@example.com"));
 }
}
