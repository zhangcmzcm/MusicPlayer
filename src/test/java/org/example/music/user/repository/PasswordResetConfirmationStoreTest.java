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

class PasswordResetConfirmationStoreTest {
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final PasswordResetConfirmationStore store = new PasswordResetConfirmationStore(redis, mock(PasswordResetConfirmationRules.class));
    @Test void should_distinguish_account_denial_and_ip_denial() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(List.of(1L,20L));
        assertFalse(store.admitAccount(1));
        assertEquals(20,assertThrows(PasswordResetConfirmationRateLimitedException.class, () -> store.admitIp("A".repeat(43))).retryAfterSeconds());
    }
    @Test void should_fail_closed_on_missing_malformed_or_unknown_replies() {
        for (List<?> reply : new List<?>[]{List.of(),List.of(2L,0L),List.of(0L,1L),List.of("0",0L)}) {
            when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenReturn(reply);
            assertThrows(PasswordResetConfirmationUnavailableException.class, () -> store.admitAccount(1));
        }
    }
    @Test void should_not_retry_unknown_execution() {
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("secret"));
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> store.admitAccount(1));
        verify(redis,times(1)).execute(any(RedisScript.class),anyList(),any(Object[].class));
    }
}
