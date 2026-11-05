package org.example.music.user.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.validation.Validation;
import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.exception.PasswordResetRequestUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
class PasswordResetSnapshotReaderTest {
 @Test void should_capture_raw_snapshot_reject_accent_and_sanitize_transaction_failure(){
  try(var factory=Validation.buildDefaultValidatorFactory()){
   var users=mock(UserRepository.class);var tm=mock(PlatformTransactionManager.class);
   when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
   var reader=new PasswordResetSnapshotReader(users,tm,new PasswordResetEmailRules(factory.getValidator()));
   var snapshot=new PasswordResetAccountSnapshot(1,"ALICE@example.com",UserRole.USER,4);
   when(users.findPasswordResetSnapshotByEmail("alice@example.com")).thenReturn(Optional.of(snapshot));
   assertEquals(snapshot,reader.capture("alice@example.com").orElseThrow());
   when(users.findPasswordResetSnapshotById(1)).thenReturn(Optional.of(snapshot));assertTrue(reader.stillMatches(snapshot,"alice@example.com"));
   when(users.findPasswordResetSnapshotById(1)).thenReturn(Optional.of(new PasswordResetAccountSnapshot(1,"ALICE@example.com",UserRole.USER,5)));
   assertFalse(reader.stillMatches(snapshot,"alice@example.com"));
   when(users.findPasswordResetSnapshotByEmail("alice@example.com")).thenReturn(Optional.of(new PasswordResetAccountSnapshot(2,"álice@example.com",UserRole.USER,0)));
   assertTrue(reader.capture("alice@example.com").isEmpty());
   when(tm.getTransaction(any())).thenThrow(new CannotCreateTransactionException("secret"));
   var failure=assertThrows(PasswordResetRequestUnavailableException.class,()->reader.capture("alice@example.com"));assertFalse(failure.toString().contains("secret"));
  }
 }
}
