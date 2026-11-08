package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
class PasswordResetTicketReadIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.10.2").withExposedPorts(6379);
    static final jakarta.validation.ValidatorFactory VALIDATORS=jakarta.validation.Validation.buildDefaultValidatorFactory();
    static LettuceConnectionFactory connection;
    static StringRedisTemplate redis;
    static PasswordResetTicketStore tickets;
    static final String P=PasswordResetTicketStore.PREFIX;
    final String digest=PasswordResetIdentifiers.generate();
    String key() { return P+"ticket:"+digest; }
    String pointer() { return P+"active:42"; }
    static long now() { return redis.execute(new DefaultRedisScript<>("local t=redis.call('TIME');return tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)",Long.class),List.of()); }
    static long expiry(String key) { return redis.execute(new DefaultRedisScript<>("return redis.call('PEXPIRETIME',KEYS[1])",Long.class),List.of(key)); }
    @BeforeAll static void connect() {
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); connection.afterPropertiesSet(); connection.start();
        redis=new StringRedisTemplate(connection); var emails=new PasswordResetEmailRules(VALIDATORS.getValidator());
        tickets=new PasswordResetTicketStore(redis,new PasswordResetTicketRules(emails),new PasswordResetConfirmationRules(emails));
    }
    @AfterAll static void close() { if(connection!=null)connection.destroy(); VALIDATORS.close(); }
    @BeforeEach void clean() { var keys=redis.keys("music:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys); }
    void create(long ending) {
        redis.opsForHash().putAll(key(),Map.of("schema","1","digest",digest,"userId","42","email","alice@example.com",
                "emailDigest",PasswordResetIdentifiers.digest("alice@example.com"),"rawEmail","ALICE@example.com","role","ADMIN",
                "tokenVersion",Long.toString(Long.MAX_VALUE),"issuedAt",Long.toString(ending-300000),"expiresAt",Long.toString(ending)));
        redis.expireAt(key(),Instant.ofEpochMilli(ending)); redis.opsForValue().set(pointer(),digest); redis.expireAt(pointer(),Instant.ofEpochMilli(ending));
    }
    @Test void should_read_exact_fields_preserving_raw_email_long_max_and_expiry() {
        long ending=now()+300000; create(ending); var value=tickets.readCurrent(42).orElseThrow();
        assertEquals(Long.MAX_VALUE,value.account().tokenVersion()); assertEquals("ALICE@example.com",value.account().email());
        assertEquals(Instant.ofEpochMilli(ending),value.period().expiresAt()); assertEquals(ending,expiry(key())); assertEquals(ending,expiry(pointer()));
    }
    @ParameterizedTest @ValueSource(strings={"missing-record","extra","schema","owner","role","version","email","raw","digest","no-ttl","pointer-ttl","pointer-type","record-type"})
    void should_reject_damage_without_repair(String damage) {
        create(now()+300000);
        switch(damage) {
            case "missing-record" -> redis.delete(key());
            case "extra" -> redis.opsForHash().put(key(),"extra","bad");
            case "schema" -> redis.opsForHash().put(key(),"schema","2");
            case "owner" -> redis.opsForHash().put(key(),"userId","43");
            case "role" -> redis.opsForHash().put(key(),"role","INVALID");
            case "version" -> redis.opsForHash().put(key(),"tokenVersion","9223372036854775808");
            case "email" -> redis.opsForHash().put(key(),"email","other@example.com");
            case "raw" -> redis.opsForHash().put(key(),"rawEmail","other@example.com");
            case "digest" -> redis.opsForHash().put(key(),"digest",PasswordResetIdentifiers.generate());
            case "no-ttl" -> redis.persist(key());
            case "pointer-ttl" -> redis.expire(pointer(),Duration.ofSeconds(10));
            case "pointer-type" -> { redis.delete(pointer());redis.opsForHash().put(pointer(),"bad","value"); }
            case "record-type" -> { redis.delete(key());redis.opsForValue().set(key(),"bad"); }
        }
        assertThrows(PasswordResetVerificationUnavailableException.class,()->tickets.readCurrent(42));
        assertTrue(Boolean.TRUE.equals(redis.hasKey(pointer())));
    }
    @Test void should_leave_orphan_untouched_without_current_pointer() {
        create(now()+300000);long end=expiry(key());redis.delete(pointer());
        assertTrue(tickets.readCurrent(42).isEmpty());assertEquals(end,expiry(key()));assertEquals(10,redis.opsForHash().size(key()));
    }
    @Test void should_return_empty_after_both_keys_expire_naturally() throws Exception {
        create(now()+250); assertTrue(tickets.readCurrent(42).isPresent());
        try(var scheduler=Executors.newSingleThreadScheduledExecutor()) {
            var elapsed=new CountDownLatch(1);scheduler.schedule(elapsed::countDown,400,TimeUnit.MILLISECONDS);assertTrue(elapsed.await(2,TimeUnit.SECONDS));
        }
        assertTrue(tickets.readCurrent(42).isEmpty());assertFalse(Boolean.TRUE.equals(redis.hasKey(key())));
    }
}
