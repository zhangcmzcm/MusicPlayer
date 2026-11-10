package org.example.music.user.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class PasswordResetTicketStoreTest {
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final PasswordResetTicketStore store=new PasswordResetTicketStore(redis,mock(PasswordResetTicketRules.class),mock(PasswordResetConfirmationRules.class));
    @Test void should_accept_only_explicit_missing_reply() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(1L));
        assertTrue(store.readCurrent(42).isEmpty());
    }
    @Test void should_fail_closed_for_malformed_replies() {
        for(List<?> reply:new List<?>[]{List.of(),List.of(2L),List.of("1"),List.of(1L,"extra"),List.of(0L)}) {
            when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(reply);
            assertThrows(PasswordResetVerificationUnavailableException.class,()->store.readCurrent(42));
        }
    }
    @Test void should_not_retry_unknown_read_and_sanitize_cause() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("private-ticket"));
        var failure=assertThrows(PasswordResetVerificationUnavailableException.class,()->store.readCurrent(42));
        assertFalse(failure.getCause().toString().contains("private-ticket"));
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }
    @Test void should_reject_unknown_exchange_reply_without_retry() {
        var challenge=new PasswordResetChallengeSnapshot("A".repeat(43),"A".repeat(43),"a@b.com",
                new PasswordResetAdmission(java.time.Instant.EPOCH,java.time.Instant.EPOCH.plusSeconds(300)),
                new PasswordResetAccountSnapshot(42,"a@b.com",UserRole.USER,0),"hash",0);
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(2L,0L,0L));
        assertThrows(PasswordResetVerificationUnavailableException.class,()->store.exchange(challenge,java.util.Optional.empty(),"A".repeat(43)));
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }

    @Test void should_fail_closed_for_malformed_lookup_replies() {
        for (List<?> reply : new List<?>[]{List.of(), List.of(2L), List.of("1"), List.of(1L,"extra"), List.of(0L)}) {
            when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(reply);
            assertThrows(PasswordResetVerificationUnavailableException.class, () -> store.readByDigest("A".repeat(43)));
        }
    }
    @Test void should_not_retry_unknown_lookup() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("private-ticket"));
        assertThrows(PasswordResetVerificationUnavailableException.class, () -> store.readByDigest("A".repeat(43)));
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }

}
