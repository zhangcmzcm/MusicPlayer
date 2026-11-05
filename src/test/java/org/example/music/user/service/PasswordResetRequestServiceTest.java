package org.example.music.user.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.user.config.PasswordResetProperties;
import org.example.music.user.domain.*;
import org.example.music.user.dto.PasswordResetRequest;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.example.music.user.exception.PasswordResetRequestUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
class PasswordResetRequestServiceTest {
 @Test void should_admit_capture_enqueue_for_missing_account_and_not_retry_unknown_enqueue(){
  var store=mock(PasswordResetRequestStore.class);var reader=mock(PasswordResetSnapshotReader.class);var service=new PasswordResetRequestService(store,reader,new PasswordResetProperties());
  var start=Instant.now();var a=new PasswordResetAdmission(start,start.plusSeconds(300));when(store.admit(anyString(),anyString())).thenReturn(a);when(reader.capture("a@example.com")).thenReturn(Optional.empty());
  var result=service.request(new PasswordResetRequest("a@example.com"),"127.0.0.1","trace.1");assertEquals(a.expiresAt(),result.expiresAt());assertTrue(PasswordResetIdentifiers.isCanonicalDigest(result.challengeId()));
  var captor=ArgumentCaptor.forClass(PasswordResetTask.class);var order=inOrder(store,reader);order.verify(store).admit(PasswordResetIdentifiers.digest("a@example.com"),PasswordResetIdentifiers.digest("127.0.0.1"));order.verify(reader).capture("a@example.com");order.verify(store).enqueue(captor.capture(),eq(1000));
  assertTrue(captor.getValue().account().isEmpty());assertEquals(PasswordResetIdentifiers.digest(result.challengeId()),captor.getValue().challengeDigest());
  clearInvocations(store);doThrow(new PasswordResetRequestUnavailableException(null)).when(store).enqueue(any(),eq(1000));assertThrows(PasswordResetRequestUnavailableException.class,()->service.request(new PasswordResetRequest("a@example.com"),"127.0.0.1","trace"));verify(store,times(1)).enqueue(any(),eq(1000));
 }
}
