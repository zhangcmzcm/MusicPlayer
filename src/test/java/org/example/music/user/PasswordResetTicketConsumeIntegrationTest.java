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
class PasswordResetTicketConsumeIntegrationTest {
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
    @Test void should_consume_both_keys_once_across_instances() throws Exception {
        var ticket=issue(Long.MAX_VALUE);
        var other=new PasswordResetTicketStore(redis,rules,challengeRules);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var first=pool.submit(()->{start.await();return tickets.consume(ticket);});
            var second=pool.submit(()->{start.await();return other.consume(ticket);});
            start.countDown();assertNotEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
        }
        assertTrue(tickets.readByDigest(ticket.digest()).isEmpty());assertTrue(tickets.readCurrent(42).isEmpty());
    }
    @Test void should_preserve_new_ticket_when_old_consumption_loses() {
        var old=issue(9007199254740993L);var fresh=issue(9007199254740993L);
        assertFalse(tickets.consume(old));assertEquals(fresh,tickets.readCurrent(42).orElseThrow());
    }
    @Test void should_allow_exchange_after_known_previous_ticket_is_consumed() {
        var old=issue(0);var challenge=active(0);
        assertTrue(tickets.consume(old));
        assertTrue(tickets.exchange(challenge,Optional.of(old),PasswordResetIdentifiers.generate()).isPresent());
    }
    @ParameterizedTest @ValueSource(strings={"record-missing","pointer-missing","email","version","issued","extra","ttl"})
    void should_reject_known_half_state_and_same_digest_mutation(String damage) {
        var ticket=issue(1);String key=T+"ticket:"+ticket.digest();
        switch(damage) {
            case "record-missing" -> redis.delete(key);
            case "pointer-missing" -> redis.delete(T+"active:42");
            case "email" -> redis.opsForHash().put(key,"rawEmail","alice@example.com");
            case "version" -> redis.opsForHash().put(key,"tokenVersion","2");
            case "issued" -> redis.opsForHash().put(key,"issuedAt","0");
            case "extra" -> redis.opsForHash().put(key,"extra","bad");
            case "ttl" -> redis.persist(key);
        }
        assertThrows(PasswordResetVerificationUnavailableException.class,()->tickets.consume(ticket));
    }
    @Test void should_return_false_when_both_known_keys_are_gone() {
        var ticket=issue(0);redis.delete(List.of(T+"ticket:"+ticket.digest(),T+"active:42"));
        assertFalse(tickets.consume(ticket));
    }
}
