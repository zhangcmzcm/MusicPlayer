package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
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

@Testcontainers @EnabledIfDockerAvailable
class PasswordResetConfirmationStoreIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
    static final jakarta.validation.ValidatorFactory VALIDATORS=jakarta.validation.Validation.buildDefaultValidatorFactory();
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static PasswordResetRequestStore producer;
    static PasswordResetConfirmationStore store;
    static PasswordResetConfirmationRules rules;
    static final String P=PasswordResetRequestStore.PREFIX, Q=PasswordResetConfirmationStore.PREFIX;
    static final String HASH=new BCryptPasswordHasher().hash("012345");
    @BeforeAll static void connect() {
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); connection.afterPropertiesSet(); connection.start();
        redis=new StringRedisTemplate(connection); producer=new PasswordResetRequestStore(redis);
        rules=new PasswordResetConfirmationRules(new PasswordResetEmailRules(VALIDATORS.getValidator()));
        store=new PasswordResetConfirmationStore(redis,rules);
    }
    @AfterAll static void close() { if(connection!=null)connection.destroy(); VALIDATORS.close(); }
    @BeforeEach void reset() { var keys=redis.keys("music:*"); if(keys!=null&&!keys.isEmpty())redis.delete(keys); }
    static long expiry(String key) { return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key)); }
    PasswordResetChallengeSnapshot create() {
        String email="alice@example.com", emailDigest=PasswordResetIdentifiers.digest(email);
        redis.delete(P+"email-cooldown:"+emailDigest);
        var admission=producer.admit(emailDigest,PasswordResetIdentifiers.digest(UUID.randomUUID().toString()));
        var task=new PasswordResetTask(PasswordResetIdentifiers.generate(),emailDigest,email,admission,
                Optional.of(new PasswordResetAccountSnapshot(42,"ALICE@example.com",UserRole.USER,Long.MAX_VALUE)),"test");
        producer.enqueue(task,1000);
        var claim=producer.claim(PasswordResetIdentifiers.generate()).orElseThrow();
        assertTrue(producer.prepare(claim,HASH)); assertTrue(producer.activate(claim)); producer.finish(claim);
        return store.read(task.challengeDigest()).orElseThrow();
    }
    @Test void should_limit_ip_and_account_without_extending_original_window() {
        String ip=PasswordResetIdentifiers.generate();
        for(int i=0;i<10;i++) { store.admitIp(ip); assertTrue(store.admitAccount(42)); }
        long ipEnd=expiry(Q+"ip:"+ip),userEnd=expiry(Q+"user:42");
        assertThrows(PasswordResetConfirmationRateLimitedException.class,()->store.admitIp(ip));
        assertFalse(store.admitAccount(42));
        assertEquals(ipEnd,expiry(Q+"ip:"+ip)); assertEquals(userEnd,expiry(Q+"user:42"));
        assertEquals("10",redis.opsForHash().get(Q+"user:42","count"));
    }
    @Test void should_restore_account_admission_after_original_expiry_naturally() throws Exception {
        long now=redis.execute(new DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
        long ending=now+250;
        redis.opsForHash().putAll(Q+"user:42",Map.of("count","10","resetAtMs",Long.toString(ending)));
        redis.expireAt(Q+"user:42",Instant.ofEpochMilli(ending));
        assertFalse(store.admitAccount(42)); assertEquals(ending,expiry(Q+"user:42"));
        try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
            var elapsed=new CountDownLatch(1); scheduler.schedule(elapsed::countDown,400,TimeUnit.MILLISECONDS);
            assertTrue(elapsed.await(2,TimeUnit.SECONDS));
        }
        assertTrue(store.admitAccount(42)); assertEquals("1",redis.opsForHash().get(Q+"user:42","count"));
    }
    @Test void should_preserve_long_version_after_nonterminal_wrong_attempt() {
        var snapshot=create(); assertEquals(Long.MAX_VALUE,snapshot.account().tokenVersion());
        String key=P+"challenge:"+snapshot.digest(); long end=expiry(key);
        store.recordFailure(snapshot); assertEquals("1",redis.opsForHash().get(key,"attempts")); assertEquals(end,expiry(key));
        assertEquals(1,store.read(snapshot.digest()).orElseThrow().attempts());
    }
    @Test void should_invalidate_fifth_failure_and_preserve_pending_new_request() {
        var snapshot=create();
        var admission=producer.admit(PasswordResetIdentifiers.digest("other@example.com"),PasswordResetIdentifiers.generate());
        var task=new PasswordResetTask(PasswordResetIdentifiers.generate(),snapshot.emailDigest(),snapshot.email(),admission,Optional.of(snapshot.account()),"test");
        producer.enqueue(task,1000);
        var pending=redis.opsForValue().get(P+"pending:"+snapshot.emailDigest());
        for(int i=0;i<5;i++) store.recordFailure(snapshot);
        assertTrue(store.read(snapshot.digest()).isEmpty()); assertEquals(pending,redis.opsForValue().get(P+"pending:"+snapshot.emailDigest()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(P+"task:"+task.challengeDigest())));
    }
    @Test void should_not_touch_new_active_when_old_mutation_arrives() {
        var old=create(); var fresh=create();
        store.recordFailure(old); assertTrue(store.read(old.digest()).isEmpty());
        assertEquals(fresh.digest(),redis.opsForValue().get(P+"active:"+fresh.emailDigest()));
        assertEquals(0,store.read(fresh.digest()).orElseThrow().attempts());
    }
    @Test void should_preserve_fixed_deadline_after_nonterminal_failure() {
        var snapshot=create();long ending=expiry(P+"challenge:"+snapshot.digest());
        store.recordFailure(snapshot);assertEquals(ending,expiry(P+"challenge:"+snapshot.digest()));
        assertEquals(1,store.read(snapshot.digest()).orElseThrow().attempts());
    }

    @ParameterizedTest @ValueSource(strings={"extra","attempts","version","ttl","pointer","half"})
    void should_fail_closed_on_corrupt_active(String damage) {
        var snapshot=create(); String key=P+"challenge:"+snapshot.digest();
        switch(damage) {
            case "extra" -> redis.opsForHash().put(key,"secret","value");
            case "attempts" -> redis.opsForHash().put(key,"attempts","5");
            case "version" -> redis.opsForHash().put(key,"tokenVersion","9223372036854775808");
            case "ttl" -> redis.persist(key);
            case "pointer" -> { redis.delete(P+"active:"+snapshot.emailDigest()); redis.opsForHash().put(P+"active:"+snapshot.emailDigest(),"bad","value"); }
            case "half" -> redis.delete(P+"active:"+snapshot.emailDigest());
        }
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->store.read(snapshot.digest()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
    }
    @ParameterizedTest @ValueSource(strings={"extra","zero","type","ttl","expiry"})
    void should_fail_closed_on_corrupt_quota(String damage) {
        assertTrue(store.admitAccount(42)); String key=Q+"user:42";
        switch(damage) {
            case "extra" -> redis.opsForHash().put(key,"extra","bad");
            case "zero" -> redis.opsForHash().put(key,"count","0");
            case "type" -> { redis.delete(key); redis.opsForValue().set(key,"bad"); }
            case "ttl" -> redis.persist(key);
            case "expiry" -> redis.opsForHash().put(key,"resetAtMs","1e9");
        }
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->store.admitAccount(42));
    }
}
