package org.example.music.user;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import org.example.music.user.domain.PasswordResetIdentifiers;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
@Testcontainers @EnabledIfDockerAvailable
class PasswordResetAdmissionIntegrationTest extends PasswordResetRedisIntegrationSupport {
 @Test void should_limit_email_and_preserve_all_counts_and_ttls_on_rejection(){
  String email=digest("alice@example.com"),ip=digest("127.0.0.1");var a=store.admit(email,ip);
  assertEquals(Duration.ofSeconds(300),Duration.between(a.admittedAt(),a.expiresAt()));
  var before=redis.opsForHash().entries(PREFIX+"email-daily:"+email);long ttl=redis.getExpire(PREFIX+"email-daily:"+email);
  var failure=assertThrows(PasswordResetRequestRateLimitedException.class,()->store.admit(email,ip));
  assertTrue(failure.retryAfterSeconds()>0&&failure.retryAfterSeconds()<=60);assertEquals(before,redis.opsForHash().entries(PREFIX+"email-daily:"+email));assertTrue(redis.getExpire(PREFIX+"email-daily:"+email)<=ttl);
 }
 @Test void should_limit_daily_email_and_fixed_ip_without_refunding(){
  String email=digest("alice@example.com");for(int n=0;n<10;n++){redis.delete(PREFIX+"email-cooldown:"+email);store.admit(email,digest("ip"+n));}
  redis.delete(PREFIX+"email-cooldown:"+email);assertThrows(PasswordResetRequestRateLimitedException.class,()->store.admit(email,digest("fresh-ip")));
  String ip=digest("shared");for(int n=0;n<5;n++)store.admit(digest("other"+n),ip);
  assertThrows(PasswordResetRequestRateLimitedException.class,()->store.admit(digest("other6"),ip));assertFalse(redis.hasKey(PREFIX+"email-daily:"+digest("other6")));
 }
 @Test void should_fail_closed_on_damaged_counter_without_clearing_it(){
  String email=digest("a");store.admit(email,digest("i"));String key=PREFIX+"email-daily:"+email;redis.persist(key);
  assertThrows(PasswordResetRequestUnavailableException.class,()->store.admit(email,digest("other")));assertTrue(redis.hasKey(key));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"zero","eleven","leading-zero","extra","wrong-type","bad-expiry","short-ttl"})
 void should_reject_corrupt_daily_counter_without_repair(String damage){
  String e=digest("alice@example.com");store.admit(e,digest("ip"));String key=PREFIX+"email-daily:"+e;
  switch(damage){case "zero"->redis.opsForHash().put(key,"count","0");case "eleven"->redis.opsForHash().put(key,"count","11");case "leading-zero"->redis.opsForHash().put(key,"count","01");case "extra"->redis.opsForHash().put(key,"extra","bad");case "wrong-type"->{redis.delete(key);redis.opsForValue().set(key,"bad");}case "bad-expiry"->redis.opsForHash().put(key,"resetAtMs","1e9");case "short-ttl"->redis.expire(key,Duration.ofSeconds(1));}
  assertThrows(PasswordResetRequestUnavailableException.class,()->store.admit(e,digest("other-ip")));assertTrue(redis.hasKey(key));
 }
 @Test void should_use_utc_day_boundary_and_maximum_blocked_wait(){
  String e=digest("alice@example.com");org.example.music.user.domain.PasswordResetAdmission admission=null;
  for(int i=0;i<10;i++){redis.delete(PREFIX+"email-cooldown:"+e);admission=store.admit(e,digest("ip"+i));}
  long end=(admission.admittedAt().toEpochMilli()/86400000+1)*86400000;
  assertEquals(Long.toString(end),redis.opsForHash().get(PREFIX+"email-daily:"+e,"resetAtMs"));
  var before=redis.opsForHash().entries(PREFIX+"email-daily:"+e);
  long wait=Math.max(redis.getExpire(PREFIX+"email-daily:"+e,java.util.concurrent.TimeUnit.MILLISECONDS),redis.getExpire(PREFIX+"email-cooldown:"+e,java.util.concurrent.TimeUnit.MILLISECONDS));
  var failure=assertThrows(PasswordResetRequestRateLimitedException.class,()->store.admit(e,digest("new-ip")));
  assertTrue(Math.abs(failure.retryAfterSeconds()-(wait+999)/1000)<=1);assertEquals(before,redis.opsForHash().entries(PREFIX+"email-daily:"+e));assertFalse(redis.hasKey(PREFIX+"ip:"+digest("new-ip")));
 }
}
