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
class PasswordResetTicketLookupIntegrationTest {
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
    @Test void should_read_producer_ticket_by_digest_without_changing_ttl() {
        var ticket=issue(Long.MAX_VALUE); long end=expiry(T+"ticket:"+ticket.digest());
        assertEquals(ticket,tickets.readByDigest(ticket.digest()).orElseThrow());
        assertEquals(end,expiry(T+"ticket:"+ticket.digest()));assertEquals(end,expiry(T+"active:42"));
    }
    @Test void should_return_empty_for_unknown_digest_without_creating_state() {
        assertTrue(tickets.readByDigest(PasswordResetIdentifiers.generate()).isEmpty());
        assertTrue(redis.keys(T+"*").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"pointer-missing","pointer-type","record-type","extra","email","raw","version","no-ttl","pointer-ttl","owner","digest"})
    void should_reject_locatable_damage_without_repair(String damage) {
        var ticket=issue(9007199254740993L);String key=T+"ticket:"+ticket.digest();String pointer=T+"active:42";
        switch(damage) {
            case "pointer-missing" -> redis.delete(pointer);
            case "pointer-type" -> {redis.delete(pointer);redis.opsForHash().put(pointer,"bad","value");}
            case "record-type" -> {redis.delete(key);redis.opsForValue().set(key,"bad");}
            case "extra" -> redis.opsForHash().put(key,"extra","bad");
            case "email" -> redis.opsForHash().put(key,"email","other@example.com");
            case "raw" -> redis.opsForHash().put(key,"rawEmail","other@example.com");
            case "version" -> redis.opsForHash().put(key,"tokenVersion","01");
            case "no-ttl" -> redis.persist(key);
            case "pointer-ttl" -> redis.expire(pointer,Duration.ofSeconds(20));
            case "owner" -> redis.opsForHash().put(key,"userId","43");
            case "digest" -> redis.opsForHash().put(key,"digest",PasswordResetIdentifiers.generate());
        }
        assertThrows(PasswordResetVerificationUnavailableException.class,()->tickets.readByDigest(ticket.digest()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(key)));
    }
    @Test void should_reject_previous_ticket_without_deleting_current_ticket() {
        var old=issue(0);var fresh=issue(0);
        assertTrue(tickets.readByDigest(old.digest()).isEmpty());
        assertEquals(fresh,tickets.readByDigest(fresh.digest()).orElseThrow());
    }
    @Test void should_read_large_version_exactly() {
        var ticket=issue(9007199254740993L);
        assertEquals(9007199254740993L,tickets.readByDigest(ticket.digest()).orElseThrow().account().tokenVersion());
    }
}
