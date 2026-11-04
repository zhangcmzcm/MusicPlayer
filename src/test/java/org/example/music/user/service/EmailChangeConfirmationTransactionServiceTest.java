package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;

class EmailChangeConfirmationTransactionServiceTest {
    final UserRepository users=mock(UserRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus tx=mock(TransactionStatus.class);
    final EmailChangeConfirmationTransactionService updater=new EmailChangeConfirmationTransactionService(users,manager);
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,Instant.EPOCH);
    final EmailChangeAccountSnapshot expected=new EmailChangeAccountSnapshot(42," Old@Example.com ",UserRole.USER,7);
    @BeforeEach void setup(){when(manager.getTransaction(any())).thenReturn(tx);when(users.changeEmailIfSnapshotMatches(42,expected.email(),7,"USER","new@example.com")).thenReturn(1);}
    void update(){updater.update(actor,session,expected,"new@example.com");}
    @Test void should_use_original_snapshot_in_single_write_and_commit(){update();verify(users).changeEmailIfSnapshotMatches(42,expected.email(),7,"USER","new@example.com");verify(manager).commit(tx);verifyNoMoreInteractions(users);}
    @Test void should_not_retry_conflict_or_upgrade_snapshot(){when(users.changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString())).thenReturn(0);when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,null,UserRole.USER,7)));assertThrows(EmailChangeConflictException.class,this::update);verify(manager).rollback(tx);verify(users,times(1)).changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString());}
    @Test void should_revoke_missing_or_changed_version(){when(users.changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString())).thenReturn(0);when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.empty());assertThrows(CurrentUserRevokedException.class,this::update);when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,expected.email(),UserRole.USER,8)));assertThrows(CurrentUserRevokedException.class,this::update);}
    @Test void should_classify_only_actual_email_duplicate(){var duplicate=new java.sql.SQLException("Duplicate entry 'hidden' for key 'users.ix_users_email'","23000",1062);when(users.changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString())).thenThrow(new DataIntegrityViolationException("private",duplicate));assertThrows(EmailChangeEmailAlreadyExistsException.class,this::update);doThrow(new DataIntegrityViolationException("ix_users_email",new java.sql.SQLException("broken","23000",1452))).when(users).changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString());assertThrows(EmailChangeConfirmationUnavailableException.class,this::update);}
    @Test void should_fail_on_start_commit_and_invalid_update_count(){doThrow(new CannotCreateTransactionException("secret")).when(manager).getTransaction(any());assertThrows(EmailChangeConfirmationUnavailableException.class,this::update);doReturn(tx).when(manager).getTransaction(any());doThrow(new TransactionSystemException("secret")).when(manager).commit(tx);assertThrows(EmailChangeConfirmationUnavailableException.class,this::update);doNothing().when(manager).commit(tx);when(users.changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString())).thenReturn(2);assertThrows(IllegalStateException.class,this::update);}
}
