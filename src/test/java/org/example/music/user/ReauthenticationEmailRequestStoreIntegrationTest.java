package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.example.music.security.BCryptPasswordHasher;
import org.example.music.user.domain.ReauthenticationEmailBinding;
import org.example.music.user.exception.*;
import org.example.music.user.repository.ReauthenticationEmailRequestStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@EnabledIfDockerAvailable
class ReauthenticationEmailRequestStoreIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.10.2")).withExposedPorts(6379);
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static ReauthenticationEmailRequestStore store;
    static final String PREFIX = "music:auth:reauth-email:";
    static final String HASH = new BCryptPasswordHasher().hash("012345");
    final ReauthenticationEmailBinding binding = new ReauthenticationEmailBinding(42, "alice@example.com", Long.MAX_VALUE);
    static String digest() { return UUID.randomUUID().toString().replace("-", "") + "A".repeat(11); }
    static String key(String kind, int id) { return PREFIX + kind + ":" + id; }
    static long expiry(String key) { return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(key)); }
    @BeforeAll static void connect() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet(); connection.start();
        redis = new StringRedisTemplate(connection); store = new ReauthenticationEmailRequestStore(redis);
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @BeforeEach void reset() { var keys = redis.keys(PREFIX + "*"); if (keys != null && !keys.isEmpty()) redis.delete(keys); }
    @Test void should_preserve_absolute_deadlines_and_replace_only_after_activation() {
        var first = store.reserve(binding, digest(), digest());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("active",42))));
        assertEquals("PREPARING", redis.opsForHash().get(key("pending",42),"state"));
        store.prepare(binding, first, HASH);
        assertEquals(first.leaseUntil().toEpochMilli(), expiry(key("pending",42)));
        store.activate(binding, first);
        var original = redis.opsForHash().entries(key("active",42));
        assertEquals(Long.toString(Long.MAX_VALUE), original.get("tokenVersion"));
        assertEquals(first.expiresAt().toEpochMilli(), expiry(key("active",42)));
        redis.expire(key("cooldown",42), Duration.ZERO);
        var next = store.reserve(binding,digest(),digest());
        store.prepare(binding,next,HASH);
        assertEquals(original, redis.opsForHash().entries(key("active",42)));
        store.cancel(binding,next.challengeDigest());
        assertEquals(original, redis.opsForHash().entries(key("active",42)));
        assertEquals(first.expiresAt().toEpochMilli(), expiry(key("active",42)));
        redis.expire(key("cooldown",42), Duration.ZERO);
        var last = store.reserve(binding,digest(),digest()); store.prepare(binding,last,HASH); store.activate(binding,last);
        store.cancel(binding, first.challengeDigest());
        assertEquals(last.challengeDigest(), redis.opsForHash().get(key("active",42),"challengeDigest"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("pending",42))));
    }
    @Test void should_keep_counters_and_deadlines_unchanged_on_cooldown_denial() {
        String ip=digest(); store.reserve(binding,digest(),ip);
        var daily=redis.opsForHash().entries(key("daily",42)); var ipState=redis.opsForHash().entries(PREFIX+"ip:"+ip);
        long deadline=expiry(key("cooldown",42));
        var ex=assertThrows(ReauthenticationEmailRateLimitedException.class,()->store.reserve(binding,digest(),ip));
        assertTrue(ex.getRetryAfterSeconds()>=1 && ex.getRetryAfterSeconds()<=60);
        assertEquals(daily,redis.opsForHash().entries(key("daily",42))); assertEquals(ipState,redis.opsForHash().entries(PREFIX+"ip:"+ip));
        assertEquals(deadline,expiry(key("cooldown",42)));
    }
    @Test void should_limit_shared_ip_to_five_distinct_users() {
        String ip=digest();
        for(int i=1;i<=5;i++) store.reserve(new ReauthenticationEmailBinding(i,"a@example.com",0),digest(),ip);
        assertThrows(ReauthenticationEmailRateLimitedException.class,()->store.reserve(binding,digest(),ip));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key("daily",42))));
        assertEquals("5",redis.opsForHash().get(PREFIX+"ip:"+ip,"count"));
    }
    @Test void should_limit_user_to_ten_requests_per_utc_day_without_refunds() {
        for(int i=0;i<10;i++) {
            var r=store.reserve(binding,digest(),digest()); store.cancel(binding,r.challengeDigest()); redis.expire(key("cooldown",42),Duration.ZERO);
        }
        long deadline=expiry(key("daily",42));
        assertThrows(ReauthenticationEmailRateLimitedException.class,()->store.reserve(binding,digest(),digest()));
        assertEquals("10",redis.opsForHash().get(key("daily",42),"count")); assertEquals(deadline,expiry(key("daily",42)));
    }
    @Test void should_calculate_utc_midnight_in_same_lua_helper() throws Exception {
        String common=new ClassPathResource("redis/reauthentication-email/common.lua").getContentAsString(StandardCharsets.UTF_8);
        var script=new DefaultRedisScript<>(common+"\nreturn utc_day_end(tonumber(ARGV[1]))",Long.class);
        assertEquals(86400000L,redis.execute(script,List.of(),"0"));
        assertEquals(86400000L,redis.execute(script,List.of(),"86399999"));
        assertEquals(172800000L,redis.execute(script,List.of(),"86400000"));
        var r=store.reserve(binding,digest(),digest());
        assertEquals((r.reservedAt().toEpochMilli()/86400000+1)*86400000,expiry(key("daily",42)));
    }
    @ParameterizedTest @ValueSource(strings={"type","extra","ttl","short-ttl","version","state","hash"})
    void should_fail_closed_on_corrupt_pending(String damage) {
        var r=store.reserve(binding,digest(),digest()); String k=key("pending",42);
        switch(damage) {
            case "type" -> { redis.delete(k); redis.opsForValue().set(k,"bad"); }
            case "extra" -> redis.opsForHash().put(k,"extra","bad");
            case "ttl" -> redis.persist(k);
            case "short-ttl" -> redis.expire(k,Duration.ofSeconds(30));
            case "version" -> redis.opsForHash().put(k,"tokenVersion","01");
            case "state" -> redis.opsForHash().put(k,"state","ACTIVE");
            case "hash" -> { redis.opsForHash().put(k,"state","PREPARED"); redis.opsForHash().put(k,"codeHash","bad"); }
        }
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->store.activate(binding,r));
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->store.reserve(binding,digest(),digest()));
    }
    @Test void should_reject_expired_owner_and_not_delete_new_pending() {
        var old=store.reserve(binding,digest(),digest()); redis.expire(key("pending",42),Duration.ZERO); redis.expire(key("cooldown",42),Duration.ZERO);
        var next=store.reserve(binding,digest(),digest());
        assertThrows(ReauthenticationEmailRequestConflictException.class,()->store.prepare(binding,old,HASH));
        store.cancel(binding,old.challengeDigest());
        assertEquals(next.challengeDigest(),redis.opsForHash().get(key("pending",42),"challengeDigest"));
        store.prepare(binding,next,HASH); store.activate(binding,next);
        assertThrows(ReauthenticationEmailRequestConflictException.class,()->store.activate(binding,old));
    }
    @ParameterizedTest @ValueSource(strings={"extra","ttl","attempts","version","hash"})
    void should_preserve_corrupt_active_and_reject_new_admission(String damage) {
        var r=store.reserve(binding,digest(),digest());store.prepare(binding,r,HASH);store.activate(binding,r);clearCooldown();String k=key("active",42);
        switch(damage){
            case "extra"->redis.opsForHash().put(k,"extra","bad");
            case "ttl"->redis.persist(k);
            case "attempts"->redis.opsForHash().put(k,"attempts","01");
            case "version"->redis.opsForHash().put(k,"tokenVersion","9223372036854775808");
            case "hash"->redis.opsForHash().put(k,"codeHash","bad");
        }
        var damaged=redis.opsForHash().entries(k);
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->store.reserve(binding,digest(),digest()));assertEquals(damaged,redis.opsForHash().entries(k));
    }
    void clearCooldown(){redis.expire(key("cooldown",42),Duration.ZERO);}
    @ParameterizedTest @ValueSource(strings={"cooldown","daily","ip"})
    void should_reject_quota_without_expiry_instead_of_repairing(String kind) {
        String ip=digest();store.reserve(binding,digest(),ip);String k=kind.equals("ip")?PREFIX+"ip:"+ip:key(kind,42);redis.persist(k);
        var original=redis.opsForHash().entries(k);
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->store.reserve(binding,digest(),ip));assertEquals(original,redis.opsForHash().entries(k));assertEquals(-1,expiry(k));
    }
    @Test void should_return_longest_wait_when_daily_and_cooldown_both_block() {
        var r=store.reserve(binding,digest(),digest());String k=key("daily",42);redis.opsForHash().put(k,"count","10");
        var failure=assertThrows(ReauthenticationEmailRateLimitedException.class,()->store.reserve(binding,digest(),digest()));
        long expected=(Math.max(expiry(k),r.leaseUntil().toEpochMilli())-r.reservedAt().toEpochMilli()+999)/1000;
        assertTrue(failure.getRetryAfterSeconds()<=expected && failure.getRetryAfterSeconds()>=expected-1);assertEquals("10",redis.opsForHash().get(k,"count"));
    }
    @Test void should_require_prepared_state_and_matching_binding_to_activate() {
        var r=store.reserve(binding,digest(),digest());assertThrows(ReauthenticationEmailRequestConflictException.class,()->store.activate(binding,r));
        store.prepare(binding,r,HASH);
        var wrong=new ReauthenticationEmailBinding(42,"other@example.com",binding.tokenVersion());
        assertThrows(ReauthenticationEmailRequestConflictException.class,()->store.activate(wrong,r));store.cancel(wrong,r.challengeDigest());assertTrue(Boolean.TRUE.equals(redis.hasKey(key("pending",42))));
        store.activate(binding,r);assertEquals("ACTIVE",redis.opsForHash().get(key("active",42),"state"));
    }
    @Test void should_admit_exactly_one_concurrent_request_for_user() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<8;i++) results.add(pool.submit(()->{ assertTrue(start.await(5,TimeUnit.SECONDS)); try {store.reserve(binding,digest(),digest());return true;}catch(ReauthenticationEmailRateLimitedException e){return false;} }));
            start.countDown(); int successes=0; for(var f:results) if(f.get(10,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes); assertEquals("1",redis.opsForHash().get(key("daily",42),"count"));
        } finally { start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
}
