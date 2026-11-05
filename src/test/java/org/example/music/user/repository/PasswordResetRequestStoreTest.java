package org.example.music.user.repository;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.example.music.user.domain.PasswordResetIdentifiers;
import org.example.music.user.exception.PasswordResetRequestUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
class PasswordResetRequestStoreTest {
 @Test void should_reject_unknown_reply_without_retry(){
  var redis=mock(StringRedisTemplate.class);var store=new PasswordResetRequestStore(redis);
  assertThrows(PasswordResetRequestUnavailableException.class,()->store.admit(PasswordResetIdentifiers.digest("email"),PasswordResetIdentifiers.digest("ip")));
 }
}
