package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.example.music.security.BCryptPasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers @EnabledIfDockerAvailable
class ReauthenticationStoreIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static ReauthenticationStore store;
    static ReauthenticationEmailRequestStore sends;
    static final String HASH=new BCryptPasswordHasher().hash("012345");
    final ReauthenticationEmailBinding binding=new ReauthenticationEmailBinding(42,"alice@example.com",Long.MAX_VALUE);
    static String digest(){return UUID.randomUUID().toString().replace("-", "")+"A".repeat(11);}
    static String activeKey(){return "music:auth:reauth-email:active:42";}
    static String quotaKey(){return "music:auth:reauth:user:42";}
    static long expiry(String key){return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key));}
    @BeforeAll static void connect(){connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);store=new ReauthenticationStore(redis);sends=new ReauthenticationEmailRequestStore(redis);}
    @AfterAll static void close(){if(connection!=null)connection.destroy();}
    @BeforeEach void reset(){var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    ReauthenticationChallengeSnapshot createActive(){var r=sends.reserve(binding,digest(),digest());sends.prepare(binding,r,HASH);sends.activate(binding,r);return store.read(binding,r.challengeDigest());}

    @Test void should_allow_ten_requests_and_keep_original_window_on_all_subsequent_calls(){
        store.admit(42);long end=expiry(quotaKey());for(int i=1;i<10;i++)store.admit(42);
        assertEquals(end,expiry(quotaKey()));var state=redis.opsForHash().entries(quotaKey());
        var failure=assertThrows(ReauthenticationRateLimitedException.class,()->store.admit(42));assertTrue(failure.getRetryAfterSeconds()>=1&&failure.getRetryAfterSeconds()<=900);
        assertEquals(state,redis.opsForHash().entries(quotaKey()));assertEquals(end,expiry(quotaKey()));assertEquals("10",state.get("count"));
        redis.expire(quotaKey(),Duration.ZERO);store.admit(42);assertEquals("1",redis.opsForHash().get(quotaKey(),"count"));
    }
    @ParameterizedTest @ValueSource(strings={"type","extra","count-zero","count-eleven","leading-zero","no-ttl","short-ttl","bad-expiry"})
    void should_reject_damaged_quota_without_repair(String damage){
        store.admit(42);String k=quotaKey();
        switch(damage){case "type"->{redis.delete(k);redis.opsForValue().set(k,"bad");}case "extra"->redis.opsForHash().put(k,"extra","bad");case "count-zero"->redis.opsForHash().put(k,"count","0");case "count-eleven"->redis.opsForHash().put(k,"count","11");case "leading-zero"->redis.opsForHash().put(k,"count","01");case "no-ttl"->redis.persist(k);case "short-ttl"->redis.expire(k,Duration.ofSeconds(10));case "bad-expiry"->redis.opsForHash().put(k,"resetAtMs","1e9");}
        assertThrows(ReauthenticationUnavailableException.class,()->store.admit(42));
    }
    @Test void should_delete_on_fifth_failure_without_touching_pending_or_expiry(){
        var snapshot=createActive();long end=expiry(activeKey());redis.expire("music:auth:reauth-email:cooldown:42",Duration.ZERO);
        sends.reserve(binding,digest(),digest());String pending="music:auth:reauth-email:pending:42";var pendingBefore=redis.opsForHash().entries(pending);long pendingEnd=expiry(pending);
        for(int i=1;i<=4;i++){store.recordFailure(snapshot);assertEquals(Integer.toString(i),redis.opsForHash().get(activeKey(),"attempts"));assertEquals(end,expiry(activeKey()));}
        store.recordFailure(snapshot);assertFalse(Boolean.TRUE.equals(redis.hasKey(activeKey())));assertEquals(pendingBefore,redis.opsForHash().entries(pending));assertEquals(pendingEnd,expiry(pending));
        assertThrows(ReauthenticationFailedException.class,()->store.consume(snapshot));
    }
    @Test void should_preserve_long_max_version_and_require_binding_and_actual_single_consumption(){
        var snapshot=createActive();assertEquals(Long.MAX_VALUE,snapshot.binding().tokenVersion());
        assertThrows(ReauthenticationFailedException.class,()->store.read(new ReauthenticationEmailBinding(42,"other@example.com",Long.MAX_VALUE),snapshot.challengeDigest()));
        assertThrows(ReauthenticationFailedException.class,()->store.read(binding,digest()));
        store.consume(snapshot);assertThrows(ReauthenticationFailedException.class,()->store.consume(snapshot));store.recordFailure(snapshot);assertFalse(Boolean.TRUE.equals(redis.hasKey(activeKey())));
    }
    @Test void should_not_affect_same_code_replacement_with_old_read_failure_or_consumption(){
        var old=createActive();redis.expire("music:auth:reauth-email:cooldown:42",Duration.ZERO);var r=sends.reserve(binding,digest(),digest());sends.prepare(binding,r,HASH);sends.activate(binding,r);
        var current=redis.opsForHash().entries(activeKey());long end=expiry(activeKey());store.recordFailure(old);assertThrows(ReauthenticationFailedException.class,()->store.consume(old));
        assertEquals(current,redis.opsForHash().entries(activeKey()));assertEquals(end,expiry(activeKey()));
        store.consume(store.read(binding,r.challengeDigest()));
    }
    @ParameterizedTest @ValueSource(strings={"type","extra","missing","no-ttl","short-ttl","version","wrong-user","hash","attempts"})
    void should_fail_closed_on_corrupt_active_in_every_operation(String damage){
        var snapshot=createActive();String k=activeKey();
        switch(damage){case "type"->{redis.delete(k);redis.opsForValue().set(k,"bad");}case "extra"->redis.opsForHash().put(k,"extra","bad");case "missing"->redis.opsForHash().delete(k,"email");case "no-ttl"->redis.persist(k);case "short-ttl"->redis.expire(k,Duration.ofSeconds(10));case "version"->redis.opsForHash().put(k,"tokenVersion","9223372036854775808");case "wrong-user"->redis.opsForHash().put(k,"userId","43");case "hash"->redis.opsForHash().put(k,"codeHash","bad");case "attempts"->redis.opsForHash().put(k,"attempts","6");}
        assertThrows(ReauthenticationUnavailableException.class,()->store.read(binding,snapshot.challengeDigest()));assertThrows(ReauthenticationUnavailableException.class,()->store.recordFailure(snapshot));assertThrows(ReauthenticationUnavailableException.class,()->store.consume(snapshot));
    }
    @Test void should_reject_exhausted_expired_or_pending_only_challenges(){
        var snapshot=createActive();redis.opsForHash().put(activeKey(),"attempts","5");assertThrows(ReauthenticationFailedException.class,()->store.read(binding,snapshot.challengeDigest()));assertThrows(ReauthenticationFailedException.class,()->store.consume(snapshot));
        redis.expire(activeKey(),Duration.ZERO);assertThrows(ReauthenticationFailedException.class,()->store.read(binding,snapshot.challengeDigest()));
        redis.expire("music:auth:reauth-email:cooldown:42",Duration.ZERO);var pending=sends.reserve(binding,digest(),digest());sends.prepare(binding,pending,HASH);assertThrows(ReauthenticationFailedException.class,()->store.read(binding,pending.challengeDigest()));
    }
    @Test void should_admit_exactly_ten_concurrent_requests() throws Exception {
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
        try{for(int i=0;i<20;i++)results.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{store.admit(42);return true;}catch(ReauthenticationRateLimitedException e){return false;}}));start.countDown();int success=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))success++;assertEquals(10,success);assertEquals("10",redis.opsForHash().get(quotaKey(),"count"));}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_consume_exactly_once_under_concurrency() throws Exception {
        var snapshot=createActive();var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
        try{for(int i=0;i<6;i++)results.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{store.consume(snapshot);return true;}catch(ReauthenticationFailedException e){return false;}}));start.countDown();int success=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
