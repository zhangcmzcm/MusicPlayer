package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
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
import org.springframework.data.redis.core.script.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers @EnabledIfDockerAvailable
class PasswordResetTicketExchangeIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
    static final jakarta.validation.ValidatorFactory VALIDATORS=jakarta.validation.Validation.buildDefaultValidatorFactory();
    static final String P=PasswordResetRequestStore.PREFIX,T=PasswordResetTicketStore.PREFIX;
    static final String HASH=new BCryptPasswordHasher().hash("012345");
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static PasswordResetTicketStore tickets;
    static PasswordResetRequestStore producer;
    static PasswordResetConfirmationStore codes;
    static PasswordResetTicketRules rules;
    static PasswordResetConfirmationRules challengeRules;
    @BeforeAll static void connect() {
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);var emails=new PasswordResetEmailRules(VALIDATORS.getValidator());
        rules=new PasswordResetTicketRules(emails);challengeRules=new PasswordResetConfirmationRules(emails);
        tickets=new PasswordResetTicketStore(redis,rules,challengeRules);producer=new PasswordResetRequestStore(redis);codes=new PasswordResetConfirmationStore(redis,challengeRules);
    }
    @AfterAll static void close() { if(connection!=null)connection.destroy();VALIDATORS.close(); }
    @BeforeEach void clean() { var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys); }
    static long expiry(String key) { return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key)); }
    PasswordResetChallengeSnapshot active(long version) {
        var email="alice@example.com";var emailDigest=PasswordResetIdentifiers.digest(email);redis.delete(P+"email-cooldown:"+emailDigest);
        var admission=producer.admit(emailDigest,PasswordResetIdentifiers.generate());
        var task=new PasswordResetTask(PasswordResetIdentifiers.generate(),emailDigest,email,admission,
                Optional.of(new PasswordResetAccountSnapshot(42,"ALICE@example.com",UserRole.ADMIN,version)),"test");
        producer.enqueue(task,1000);var claim=producer.claim(PasswordResetIdentifiers.generate()).orElseThrow();
        assertTrue(producer.prepare(claim,HASH));assertTrue(producer.activate(claim));producer.finish(claim);
        return codes.read(task.challengeDigest()).orElseThrow();
    }
    PasswordResetTicketSnapshot issue(long version) {
        var challenge=active(version);var candidate=PasswordResetIdentifiers.generate();
        assertTrue(tickets.exchange(challenge,tickets.readCurrent(42),candidate).isPresent());return tickets.readCurrent(42).orElseThrow();
    }
    @Test void should_exchange_real_active_and_set_independent_fixed_ticket_deadline() {
        var challenge=active(Long.MAX_VALUE);long codeEnd=expiry(P+"challenge:"+challenge.digest());
        var quota=redis.opsForHash().entries(P+"email-daily:"+challenge.emailDigest());
        var candidate=PasswordResetIdentifiers.generate();var period=tickets.exchange(challenge,Optional.empty(),candidate).orElseThrow();
        var ticket=tickets.readCurrent(42).orElseThrow();assertEquals(candidate,ticket.digest());assertEquals(challenge.account(),ticket.account());
        assertEquals(300000,period.expiresAt().toEpochMilli()-period.issuedAt().toEpochMilli());assertTrue(period.expiresAt().toEpochMilli()>=codeEnd);
        assertEquals(period.expiresAt().toEpochMilli(),expiry(T+"ticket:"+candidate));assertEquals(expiry(T+"ticket:"+candidate),expiry(T+"active:42"));
        assertEquals(10,redis.opsForHash().size(T+"ticket:"+candidate));assertTrue(codes.read(challenge.digest()).isEmpty());
        assertEquals(quota,redis.opsForHash().entries(P+"email-daily:"+challenge.emailDigest()));
        assertTrue(tickets.exchange(challenge,Optional.empty(),PasswordResetIdentifiers.generate()).isEmpty());
    }
    @Test void should_replace_only_expected_previous_ticket() {
        var old=issue(0);var challenge=active(0);var candidate=PasswordResetIdentifiers.generate();
        redis.opsForValue().set(T+"other-purpose","untouched");
        assertTrue(tickets.exchange(challenge,Optional.of(old),candidate).isPresent());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(T+"ticket:"+old.digest())));assertEquals(candidate,tickets.readCurrent(42).orElseThrow().digest());
        assertEquals("untouched",redis.opsForValue().get(T+"other-purpose"));
    }
    @Test void should_exchange_once_across_two_store_instances() throws Exception {
        var challenge=active(0);var other=new PasswordResetTicketStore(redis,rules,challengeRules);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return tickets.exchange(challenge,Optional.empty(),PasswordResetIdentifiers.generate()).isPresent();});
            var b=pool.submit(()->{start.await();return other.exchange(challenge,Optional.empty(),PasswordResetIdentifiers.generate()).isPresent();});start.countDown();
            assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,redis.keys(T+"ticket:*").size());
    }
    @Test void should_allow_nonterminal_attempt_change_but_reject_fifth_failure() {
        var challenge=active(0);codes.recordFailure(challenge);
        assertTrue(tickets.exchange(challenge,Optional.empty(),PasswordResetIdentifiers.generate()).isPresent());
        var next=active(0);var old=tickets.readCurrent(42);for(int i=0;i<5;i++)codes.recordFailure(next);
        assertTrue(tickets.exchange(next,old,PasswordResetIdentifiers.generate()).isEmpty());assertEquals(old,tickets.readCurrent(42));
    }
    @Test void should_not_touch_new_active_when_old_exchange_or_failure_arrives() {
        var old=active(0);var fresh=active(0);codes.recordFailure(old);
        assertTrue(tickets.exchange(old,Optional.empty(),PasswordResetIdentifiers.generate()).isEmpty());
        assertEquals(0,codes.read(fresh.digest()).orElseThrow().attempts());assertTrue(tickets.readCurrent(42).isEmpty());
    }
    @Test void should_reject_candidate_collision_without_consuming_or_replacing() {
        var old=issue(0);var challenge=active(0);var candidate=PasswordResetIdentifiers.generate();redis.opsForValue().set(T+"ticket:"+candidate,"occupied");
        assertThrows(PasswordResetVerificationUnavailableException.class,()->tickets.exchange(challenge,Optional.of(old),candidate));
        assertTrue(codes.read(challenge.digest()).isPresent());assertEquals(old,tickets.readCurrent(42).orElseThrow());assertEquals("occupied",redis.opsForValue().get(T+"ticket:"+candidate));
    }
    @ParameterizedTest @ValueSource(strings={"extra","same-digest-change","missing-pointer","missing-record","ttl"})
    void should_precheck_damage_before_any_write(String damage) {
        var old=issue(0);var challenge=active(0);var key=T+"ticket:"+old.digest();var candidate=PasswordResetIdentifiers.generate();
        switch(damage) {
            case "extra" -> redis.opsForHash().put(key,"extra","bad");
            case "same-digest-change" -> redis.opsForHash().put(key,"role","USER");
            case "missing-pointer" -> redis.delete(T+"active:42");
            case "missing-record" -> redis.delete(key);
            case "ttl" -> redis.persist(key);
        }
        assertThrows(PasswordResetVerificationUnavailableException.class,()->tickets.exchange(challenge,Optional.of(old),candidate));
        assertTrue(codes.read(challenge.digest()).isPresent());assertFalse(Boolean.TRUE.equals(redis.hasKey(T+"ticket:"+candidate)));
    }
    @Test void should_keep_higher_version_using_values_above_double_precision() {
        var high=issue(9007199254740993L);var low=active(9007199254740992L);
        assertTrue(tickets.exchange(low,Optional.of(high),PasswordResetIdentifiers.generate()).isEmpty());
        assertEquals(high,tickets.readCurrent(42).orElseThrow());assertTrue(codes.read(low.digest()).isPresent());
    }
    @Test void should_reject_changed_previous_pointer_without_consuming_current_code() {
        var old=issue(0);var fresh=issue(0);var challenge=active(0);
        assertTrue(tickets.exchange(challenge,Optional.of(old),PasswordResetIdentifiers.generate()).isEmpty());
        assertEquals(fresh,tickets.readCurrent(42).orElseThrow());assertTrue(codes.read(challenge.digest()).isPresent());
    }
    @Test void should_allow_expected_pair_to_expire_naturally() throws Exception {
        var old=issue(0);var now=redis.execute(new DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of());
        long end=now+300;var period=new PasswordResetTicketPeriod(Instant.ofEpochMilli(end-300000),Instant.ofEpochMilli(end));
        redis.opsForHash().putAll(T+"ticket:"+old.digest(),Map.of("issuedAt",Long.toString(end-300000),"expiresAt",Long.toString(end)));
        redis.expireAt(T+"ticket:"+old.digest(),period.expiresAt());redis.expireAt(T+"active:42",period.expiresAt());
        var expected=tickets.readCurrent(42);var challenge=active(0);
        try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
            var elapsed=new CountDownLatch(1);scheduler.schedule(elapsed::countDown,450,TimeUnit.MILLISECONDS);assertTrue(elapsed.await(2,TimeUnit.SECONDS));
        }
        assertTrue(tickets.exchange(challenge,expected,PasswordResetIdentifiers.generate()).isPresent());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void should_distinguish_execution_effects_when_reply_is_unknown(boolean executed) {
        var challenge=active(0);var candidate=PasswordResetIdentifiers.generate();var faulty=spy(redis);
        doAnswer(i->{if(executed)i.callRealMethod();throw new IllegalStateException("private-ticket");}).when(faulty).execute(any(RedisScript.class),anyList(),any(Object[].class));
        var store=new PasswordResetTicketStore(faulty,rules,challengeRules);
        assertThrows(PasswordResetVerificationUnavailableException.class,()->store.exchange(challenge,Optional.empty(),candidate));
        verify(faulty,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertEquals(executed,tickets.readCurrent(42).isPresent());assertEquals(!executed,codes.read(challenge.digest()).isPresent());
    }
}
