package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetRequestFailureIntegrationTest extends PasswordResetRequestIntegrationSupport {
 @ParameterizedTest @ValueSource(strings={"admit","enqueue"})
 void should_report_503_without_automatic_retry_after_real_acceptance_operation_loses_reply(String operation)throws Exception{
  if(operation.equals("admit"))doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).admit(anyString(),anyString());
  else doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).enqueue(any(),anyInt());
  var response=request("missing@example.com");assertEquals(503,response.getResponse().getStatus());assertEquals("1",redis.opsForHash().get(P+"email-daily:"+digest("missing@example.com"),"count"));
  if(operation.equals("enqueue")){verify(store,times(1)).enqueue(any(),anyInt());assertEquals(1L,redis.opsForZSet().zCard(P+"outstanding"));}else{verify(store,never()).enqueue(any(),anyInt());assertEquals(0L,redis.opsForZSet().zCard(P+"outstanding"));}
  verifyNoInteractions(mail);
 }
 @Test void should_never_send_after_real_claim_loses_reply_or_recover_abandoned_claim()throws Exception{
  save("alice@example.com",UserRole.USER);accept("alice@example.com");doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).claim(anyString());
  assertThrows(PasswordResetRequestUnavailableException.class,processor::processNext);verifyNoInteractions(mail);doCallRealMethod().when(store).claim(anyString());assertFalse(processor.processNext());assertEquals(1L,redis.opsForZSet().zCard(P+"outstanding"));assertNull(active("alice@example.com"));
 }
 @Test void should_not_send_or_restore_old_state_after_real_prepare_loses_reply()throws Exception{
  var user=save("alice@example.com",UserRole.USER);var before=row(user);accept("alice@example.com");doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).prepare(any(),anyString());
  assertTrue(processor.processNext());verifyNoInteractions(mail);assertEquals(before,row(user));assertNull(active("alice@example.com"));assertEquals(0L,redis.opsForZSet().zCard(P+"outstanding"));
 }
 @Test void should_keep_new_active_after_real_activation_loses_reply()throws Exception{
  var user=save("alice@example.com",UserRole.USER);var before=row(user);var old=accept("alice@example.com");processor.processNext();cooldown("alice@example.com");var newer=accept("alice@example.com");
  doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).activate(any());
  assertTrue(processor.processNext());assertEquals(newer.digest(),active("alice@example.com"));assertTrue(activeState(old).isEmpty());assertFalse(activeState(newer).isEmpty());assertEquals(before,row(user));verify(mail,times(2)).send(any());assertFalse(processor.processNext());
 }
 @ParameterizedTest @ValueSource(strings={"finish","reap"})
 void should_not_resend_after_cleanup_reply_is_unknown(String operation)throws Exception{
  save("alice@example.com",UserRole.USER);var a=accept("alice@example.com");
  if(operation.equals("finish"))doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).finish(any());
  else doAnswer(i->{i.callRealMethod();throw new PasswordResetRequestUnavailableException(null);}).when(store).reap(anyInt());
  assertTrue(processor.processNext());if(operation.equals("reap"))assertThrows(PasswordResetRequestUnavailableException.class,()->store.reap(100));assertEquals(a.digest(),active("alice@example.com"));assertFalse(processor.processNext());verify(mail,times(1)).send(any());
 }
 @Test void should_never_rebind_or_activate_after_database_recheck_failure()throws Exception{
  save("alice@example.com",UserRole.USER);accept("alice@example.com");doThrow(new PasswordResetRequestUnavailableException(null)).when(reader).stillMatches(any(),anyString());assertTrue(processor.processNext());verifyNoInteractions(mail);assertNull(active("alice@example.com"));assertFalse(processor.processNext());
 }
 @Test void should_leave_activated_request_after_202_response_is_ignored()throws Exception{
  save("alice@example.com",UserRole.USER);var first=request("alice@example.com");assertEquals(202,first.getResponse().getStatus());processor.processNext();assertNotNull(active("alice@example.com"));assertEquals(429,request("alice@example.com").getResponse().getStatus());verify(mail,times(1)).send(any());
 }
 @ParameterizedTest @ValueSource(strings={"begin","read","commit"})
 void should_report_storage_failure_without_queueing_or_refunding(String phase)throws Exception{
  save("alice@example.com",UserRole.USER);
  switch(phase){
   case "begin"->doThrow(new org.springframework.transaction.CannotCreateTransactionException("secret")).when(transactions).getTransaction(any());
   case "read"->doThrow(new org.springframework.dao.DataAccessResourceFailureException("secret")).when(users).findPasswordResetSnapshotByEmail(anyString());
   case "commit"->doAnswer(i->{i.callRealMethod();throw new org.springframework.transaction.TransactionSystemException("secret");}).when(transactions).commit(any());
  }
  var result=request("alice@example.com");assertEquals(503,result.getResponse().getStatus());assertFalse(result.getResponse().getContentAsString().contains("secret"));verifyNoInteractions(mail);assertEquals(0L,redis.opsForZSet().zCard(P+"outstanding"));assertEquals("1",redis.opsForHash().get(P+"email-daily:"+digest("alice@example.com"),"count"));
 }
 @Test void should_not_activate_after_actual_deadline_passes_during_smtp()throws Exception{
  var user=save("alice@example.com",UserRole.USER);var row=reader.capture("alice@example.com").orElseThrow();
  var now=redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
  var end=java.time.Instant.ofEpochMilli(now+1500);var task=new PasswordResetTask(PasswordResetIdentifiers.generate(),digest("alice@example.com"),"alice@example.com",new PasswordResetAdmission(end.minusSeconds(300),end),Optional.of(row),"expiry-test");store.enqueue(task,1000);
  var scheduler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();var release=new java.util.concurrent.CountDownLatch(1);
  doAnswer(i->{scheduler.schedule(release::countDown,Math.max(1,end.toEpochMilli()-System.currentTimeMillis()+100),java.util.concurrent.TimeUnit.MILLISECONDS);assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return null;}).when(mail).send(any());
  try{assertTrue(processor.processNext());assertNull(active("alice@example.com"));assertEquals(1,store.reap(100));assertEquals(0L,redis.opsForZSet().zCard(P+"outstanding"));}
  finally{release.countDown();scheduler.shutdownNow();assertTrue(scheduler.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
 }
}
