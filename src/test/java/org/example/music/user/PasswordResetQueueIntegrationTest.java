package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.example.music.security.BCryptPasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
@Testcontainers @EnabledIfDockerAvailable
class PasswordResetQueueIntegrationTest extends PasswordResetRedisIntegrationSupport {
 static final String HASH=new BCryptPasswordHasher().hash("012345");
 PasswordResetTask task(String email){return new PasswordResetTask(PasswordResetIdentifiers.generate(),digest(email),email,store.admit(digest(email),digest(UUID.randomUUID().toString())),Optional.of(new PasswordResetAccountSnapshot(42,email,UserRole.USER,Long.MAX_VALUE)),"test-trace");}
 PasswordResetTask next(PasswordResetTask previous){redis.delete(PREFIX+"email-cooldown:"+previous.emailDigest());return task(previous.email());}
 @Test void should_claim_once_across_two_stores_and_activate_with_original_deadline(){
  var task=task("alice@example.com");store.enqueue(task,2);var claim=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();
  assertTrue(new PasswordResetRequestStore(redis).claim(PasswordResetIdentifiers.generate()).isEmpty());
  assertTrue(store.prepare(claim,HASH));assertTrue(store.activate(claim));store.finish(claim);
  assertEquals(task.challengeDigest(),redis.opsForValue().get(PREFIX+"active:"+task.emailDigest()));
  assertEquals("9223372036854775807",redis.opsForHash().get(PREFIX+"challenge:"+task.challengeDigest(),"tokenVersion"));
  assertEquals(0L,redis.opsForZSet().zCard(PREFIX+"outstanding"));
 }
 @Test void should_keep_claimed_old_task_counted_and_prevent_stale_activation(){
  var old=task("alice@example.com");store.enqueue(old,2);var claim=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();
  var fresh=next(old);store.enqueue(fresh,2);assertEquals(2L,redis.opsForZSet().zCard(PREFIX+"outstanding"));
  assertFalse(store.prepare(claim,HASH));store.finish(claim);assertEquals(1L,redis.opsForZSet().zCard(PREFIX+"outstanding"));
  assertEquals(fresh.challengeDigest(),redis.opsForValue().get(PREFIX+"pending:"+fresh.emailDigest()));
  var newer=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();assertTrue(store.prepare(newer,HASH));assertTrue(store.activate(newer));store.finish(claim);
  assertEquals(fresh.challengeDigest(),redis.opsForValue().get(PREFIX+"active:"+fresh.emailDigest()));
 }
 @Test void should_replace_unclaimed_task_but_reject_full_queue_without_mutating_pending(){
  var old=task("a@example.com");store.enqueue(old,1);var fresh=next(old);store.enqueue(fresh,1);assertEquals(1L,redis.opsForZSet().zCard(PREFIX+"outstanding"));
  var other=task("b@example.com");assertThrows(PasswordResetRequestUnavailableException.class,()->store.enqueue(other,1));assertFalse(redis.hasKey(PREFIX+"pending:"+other.emailDigest()));
  assertThrows(PasswordResetRequestUnavailableException.class,()->store.enqueue(old,2));assertEquals(fresh.challengeDigest(),redis.opsForValue().get(PREFIX+"pending:"+fresh.emailDigest()));
 }
 @Test void should_keep_newer_watermark_after_finish_and_reject_slow_old_enqueue(){
  var old=task("a@example.com");var fresh=next(old);store.enqueue(fresh,2);var claim=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();store.finish(claim);
  assertThrows(PasswordResetRequestUnavailableException.class,()->store.enqueue(old,2));
 }
 @Test void should_reap_orphaned_expired_index_without_removing_new_pending(){
  var old=task("a@example.com");store.enqueue(old,2);var fresh=next(old);store.enqueue(fresh,2);
  redis.opsForZSet().add(PREFIX+"outstanding",old.challengeDigest(),1);assertEquals(1,store.reap(100));
  assertEquals(fresh.challengeDigest(),redis.opsForValue().get(PREFIX+"pending:"+fresh.emailDigest()));
 }
 @Test void should_claim_once_under_parallel_store_instances() throws Exception {
  store.enqueue(task("parallel@example.com"),2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{
   Callable<Boolean> operation=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return new PasswordResetRequestStore(redis).claim(PasswordResetIdentifiers.generate()).isPresent();};
   var first=pool.submit(operation);var second=pool.submit(operation);assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
   assertNotEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
  }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
 }
 @Test void should_preserve_old_active_until_replacement_success(){
  var old=task("a@example.com");store.enqueue(old,2);var original=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();assertTrue(store.prepare(original,HASH));assertTrue(store.activate(original));
  var fresh=next(old);store.enqueue(fresh,2);var newer=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();assertTrue(store.prepare(newer,HASH));store.finish(newer);
  assertEquals(old.challengeDigest(),redis.opsForValue().get(PREFIX+"active:"+old.emailDigest()));assertTrue(redis.hasKey(PREFIX+"challenge:"+old.challengeDigest()));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"extra","missing","no-ttl","short-ttl","version","hash","phase","email","watermark","pending"})
 void should_fail_closed_on_damaged_task_without_clearing_it(String damage){
  var t=task("a@example.com");store.enqueue(t,2);String key=PREFIX+"task:"+t.challengeDigest();
  switch(damage){
   case "extra"->redis.opsForHash().put(key,"extra","bad");case "missing"->redis.opsForHash().delete(key,"role");case "no-ttl"->redis.persist(key);case "short-ttl"->redis.expire(key,Duration.ofSeconds(5));
   case "version"->redis.opsForHash().put(key,"tokenVersion","9223372036854775808");case "hash"->redis.opsForHash().put(key,"codeHash",HASH);case "phase"->redis.opsForHash().put(key,"phase","CLAIMED");case "email"->redis.opsForHash().put(key,"email","other@example.com");
   case "watermark"->redis.opsForHash().put(PREFIX+"latest:"+t.emailDigest(),"admittedAt","01");case "pending"->{redis.delete(PREFIX+"pending:"+t.emailDigest());redis.opsForHash().put(PREFIX+"pending:"+t.emailDigest(),"bad","bad");}
  }
  assertThrows(PasswordResetRequestUnavailableException.class,()->store.claim(PasswordResetIdentifiers.generate()));assertTrue(redis.hasKey(key));
 }
 @Test void should_reject_corrupt_previous_active_without_replacing_it(){
  var t=task("a@example.com");store.enqueue(t,2);var c=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();store.prepare(c,HASH);store.activate(c);
  var n=next(t);store.enqueue(n,2);var next=store.claim(PasswordResetIdentifiers.generate()).orElseThrow();store.prepare(next,HASH);
  redis.opsForHash().put(PREFIX+"challenge:"+t.challengeDigest(),"role","UNKNOWN");assertThrows(PasswordResetRequestUnavailableException.class,()->store.activate(next));
  assertEquals(t.challengeDigest(),redis.opsForValue().get(PREFIX+"active:"+t.emailDigest()));
 }
}
