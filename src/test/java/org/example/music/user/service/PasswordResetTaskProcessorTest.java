package org.example.music.user.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.email.*;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.example.music.security.PasswordHasher;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
class PasswordResetTaskProcessorTest {
 final PasswordResetRequestStore store=mock(PasswordResetRequestStore.class);
 final PasswordResetSnapshotReader reader=mock(PasswordResetSnapshotReader.class);
 final PasswordHasher hasher=mock(PasswordHasher.class);final SecureVerificationCodeGenerator codes=mock(SecureVerificationCodeGenerator.class);
 final BoundedPasswordResetEmailDelivery delivery=mock(BoundedPasswordResetEmailDelivery.class);
 final PasswordResetTaskProcessor processor=new PasswordResetTaskProcessor(store,reader,hasher,codes,delivery);
 PasswordResetClaim claim(boolean account){var now=Instant.now();return new PasswordResetClaim(new PasswordResetTask(PasswordResetIdentifiers.generate(),PasswordResetIdentifiers.digest("a@example.com"),"a@example.com",new PasswordResetAdmission(now,now.plusSeconds(300)),account?Optional.of(new PasswordResetAccountSnapshot(1,"a@example.com",UserRole.USER,0)):Optional.empty(),"test-trace"),PasswordResetIdentifiers.generate());}
 @Test void should_finish_unmatched_task_without_smtp_or_rebinding(){var c=claim(false);when(store.claim(anyString())).thenReturn(Optional.of(c));assertTrue(processor.processNext());verify(store).finish(c);verifyNoInteractions(delivery,reader,hasher,codes);}
 @Test void should_recheck_before_send_and_before_activation(){
  var c=claim(true);when(store.claim(anyString())).thenReturn(Optional.of(c));when(reader.stillMatches(any(),anyString())).thenReturn(true);when(codes.generate()).thenReturn("012345");when(hasher.hash("012345")).thenReturn("hash");when(store.prepare(c,"hash")).thenReturn(true);when(store.activate(c)).thenReturn(true);
  assertTrue(processor.processNext());var order=inOrder(reader,store,delivery);order.verify(store).claim(anyString());order.verify(reader).stillMatches(any(),anyString());order.verify(store).prepare(c,"hash");order.verify(delivery).send(any());order.verify(reader).stillMatches(any(),anyString());order.verify(store).activate(c);order.verify(store).finish(c);
 }
 @Test void should_not_activate_or_retry_after_smtp_failure(){
  var c=claim(true);when(store.claim(anyString())).thenReturn(Optional.of(c));when(reader.stillMatches(any(),anyString())).thenReturn(true);when(codes.generate()).thenReturn("012345");when(hasher.hash(anyString())).thenReturn("hash");when(store.prepare(c,"hash")).thenReturn(true);doThrow(new PasswordResetSendFailedException(null)).when(delivery).send(any());
  assertTrue(processor.processNext());verify(delivery,times(1)).send(any());verify(store,never()).activate(any());verify(store).finish(c);
 }
 @Test void should_not_send_after_unknown_claim(){when(store.claim(anyString())).thenThrow(new PasswordResetRequestUnavailableException(null));assertThrows(PasswordResetRequestUnavailableException.class,processor::processNext);verifyNoInteractions(delivery);verify(store,never()).finish(any());}
}
