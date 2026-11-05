package org.example.music.user.config;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
class PasswordResetPropertiesTest {
 @Test void should_use_defaults_and_reject_nonpositive_or_unrepresentable_timeout(){
  try(var factory=Validation.buildDefaultValidatorFactory()){
   var p=new PasswordResetProperties();var v=factory.getValidator();assertTrue(v.validate(p).isEmpty());assertEquals(1000,p.getQueueCapacity());assertEquals(4,p.getWorkerConcurrency());assertEquals(Duration.ofSeconds(20),p.getSendTimeout());
   p.setQueueCapacity(0);assertFalse(v.validate(p).isEmpty());p.setQueueCapacity(1000);
   p.setWorkerConcurrency(0);assertFalse(v.validate(p).isEmpty());p.setWorkerConcurrency(4);
   for(var d:new Duration[]{Duration.ZERO,Duration.ofNanos(1),Duration.ofSeconds(Long.MAX_VALUE),Duration.ofMillis((long)Integer.MAX_VALUE+1)}){p.setSendTimeout(d);assertFalse(v.validate(p).isEmpty());}
  }
 }
}
