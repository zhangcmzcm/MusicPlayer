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
class PasswordResetAdmissionIntegrationTest {
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
 static LettuceConnectionFactory connection;static StringRedisTemplate redis;static PasswordResetRequestStore store;
 static final String PREFIX="music:auth:password-reset:";
 @BeforeAll static void connect(){connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);store=new PasswordResetRequestStore(redis);}
 @AfterAll static void close(){if(connection!=null)connection.destroy();}
 @BeforeEach void clean(){var keys=redis.keys(PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
 static String digest(String s){return PasswordResetIdentifiers.digest(s);}
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
}
