package org.example.music.user.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.example.music.user.domain.EmailChangeBinding;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class EmailChangeConfirmationStoreTest {
    static final jakarta.validation.ValidatorFactory VALIDATORS=jakarta.validation.Validation.buildDefaultValidatorFactory();

    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final EmailChangeConfirmationStore store = new EmailChangeConfirmationStore(redis,new org.example.music.user.domain.EmailChangeConfirmationRules(VALIDATORS.getValidator()));
    final EmailChangeBinding binding = new EmailChangeBinding(42,"old@example.com","alice@example.com",Long.MAX_VALUE);
    final String digest="A".repeat(43);
    final String hash="$2a$10$"+"A".repeat(53);
    @org.junit.jupiter.api.AfterAll static void close(){VALIDATORS.close();}
    void reply(Object value){doReturn(value).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));}
    static Stream<List<?>> brokenAdmission(){return Stream.of(null,List.of(),List.of(0L),List.of("0",0L),List.of(0L,1L),List.of(1L,0L),List.of(1L,901L),List.of(2L,0L),List.of(9L,0L));}
    @ParameterizedTest @MethodSource("brokenAdmission")
    void should_fail_closed_on_bad_admission_reply(List<?> reply){reply(reply);assertThrows(EmailChangeConfirmationUnavailableException.class,()->store.admit(42));}
    @Test void should_require_confirmed_admission_and_bounded_retry_after(){
        reply(List.of(0L,0L));assertDoesNotThrow(()->store.admit(42));reply(List.of(1L,900L));assertEquals(900,assertThrows(EmailChangeConfirmationRateLimitedException.class,()->store.admit(42)).getRetryAfterSeconds());
    }
    @Test void should_validate_read_protocol_and_confirm_each_mutation(){
        reply(List.of(0L,"ACTIVE",digest,"42","old@example.com","alice@example.com",Long.toString(Long.MAX_VALUE),"1000","301000",hash,"0"));
        var snapshot=store.read(binding.userId(),digest);assertEquals(Instant.ofEpochMilli(301000),snapshot.expiresAt());
        reply(List.of(0L,5L));assertDoesNotThrow(()->store.recordFailure(snapshot));reply(List.of(1L,0L));assertDoesNotThrow(()->store.recordFailure(snapshot));
        reply(List.of(0L));assertDoesNotThrow(()->store.consume(snapshot));reply(List.of(1L));assertThrows(EmailChangeVerificationFailedException.class,()->store.consume(snapshot));
        assertThrows(EmailChangeVerificationFailedException.class,()->store.read(binding.userId(),digest));
        reply(List.of(2L));assertThrows(EmailChangeConfirmationUnavailableException.class,()->store.consume(snapshot));
        reply(List.of(0L,6L));assertThrows(EmailChangeConfirmationUnavailableException.class,()->store.recordFailure(snapshot));
        reply(null);assertThrows(EmailChangeConfirmationUnavailableException.class,()->store.read(binding.userId(),digest));
    }
    @Test void should_reject_wrong_binding_noncanonical_numbers_and_unexpected_read_shapes(){
        for(var invalid:List.of(
                List.of(0L,"ACTIVE",digest,"43","old@example.com","alice@example.com","0","1000","301000",hash,"0"),
                List.of(0L,"ACTIVE",digest,"42","old@example.com","alice@example.com",Long.toString(Long.MAX_VALUE),"01000","301000",hash,"0"),
                List.of(0L,"ACTIVE",digest,"42","old@example.com","alice@example.com",Long.toString(Long.MAX_VALUE),"1000","301001",hash,"0"),
                List.of(0L,"ACTIVE",digest,"42","old@example.com","alice@example.com",Long.toString(Long.MAX_VALUE),"1000","301000",hash,"5"),
                List.of(0L,"ACTIVE",digest,"42","old@example.com","alice@example.com",Long.toString(Long.MAX_VALUE),"1000","301000","bad","0"),
                List.of(0L,123))){reply(invalid);assertThrows(EmailChangeConfirmationUnavailableException.class,()->store.read(binding.userId(),digest));}
    }
    @Test void should_translate_dependency_failure_without_retry(){
        doThrow(new DataAccessResourceFailureException("secret")).when(redis).execute(any(RedisScript.class),anyList(),any(Object[].class));
        assertThrows(EmailChangeConfirmationUnavailableException.class,()->store.admit(42));verify(redis,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
        var diagnostic=EmailChangeDiagnostics.sanitized(new RuntimeException("secret",new RuntimeException("nested")));
        assertFalse(diagnostic.getMessage().contains("secret"));assertNull(diagnostic.getCause());assertEquals(0,diagnostic.getSuppressed().length);
    }
}
