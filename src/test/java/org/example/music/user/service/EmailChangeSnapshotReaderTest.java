package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.*;

class EmailChangeSnapshotReaderTest {
    final UserRepository users=mock(UserRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus tx=mock(TransactionStatus.class);
    final EmailChangeSnapshotReader reader=new EmailChangeSnapshotReader(users,manager);
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,Instant.EPOCH);
    @BeforeEach void setup(){when(manager.getTransaction(any())).thenAnswer(i->{assertTrue(((TransactionDefinition)i.getArgument(0)).isReadOnly());return tx;});when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42," Old@Example.com ",UserRole.USER,7)));when(users.findEmailOwnerId(anyString())).thenReturn(Optional.empty());}
    @Test void should_load_minimal_snapshot_normalize_and_commit(){var b=reader.loadBinding(actor,session,"new@example.com");assertEquals("old@example.com",b.currentEmail());assertEquals(4,EmailChangeAccountSnapshot.class.getRecordComponents().length);verify(manager).commit(tx);assertFalse(b.toString().contains("old@"));}
    @Test void should_allow_unbound_and_classify_email_owner(){when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,null,UserRole.USER,7)));var b=reader.loadBinding(actor,session,"new@example.com");assertNull(b.currentEmail());reader.requireTargetAvailable(b);when(users.findEmailOwnerId(anyString())).thenReturn(Optional.of(42));assertThrows(EmailUnchangedException.class,()->reader.requireTargetAvailable(b));when(users.findEmailOwnerId(anyString())).thenReturn(Optional.of(9));assertThrows(EmailChangeEmailAlreadyExistsException.class,()->reader.requireTargetAvailable(b));}
    @Test void should_reject_same_address_before_lookup(){var b=reader.loadBinding(actor,session,"old@example.com");assertThrows(EmailUnchangedException.class,()->reader.requireTargetAvailable(b));verify(users,never()).findEmailOwnerId(anyString());}
    @Test void should_revoke_missing_or_changed_version_and_role(){when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.empty());assertThrows(CurrentUserRevokedException.class,()->reader.loadBinding(actor,session,"new@example.com"));when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,null,UserRole.USER,8)));assertThrows(CurrentUserRevokedException.class,()->reader.loadBinding(actor,session,"new@example.com"));when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,null,UserRole.USER,7)));assertThrows(CurrentUserRevokedException.class,()->reader.loadBinding(new AuthenticatedUserPrincipal(42,UserRole.ADMIN),session,"new@example.com"));}
    @ParameterizedTest @ValueSource(strings={"broken","a\r\n@example.com","Name <a@example.com>"}) void should_reject_corrupt_stored_address(String email){when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,email,UserRole.USER,7)));assertThrows(EmailChangeRequestUnavailableException.class,()->reader.loadBinding(actor,session,"new@example.com"));}
    @Test void should_translate_start_query_and_commit_failures(){doThrow(new CannotCreateTransactionException("secret")).when(manager).getTransaction(any());assertThrows(EmailChangeRequestUnavailableException.class,()->reader.loadBinding(actor,session,"new@example.com"));doReturn(tx).when(manager).getTransaction(any());when(users.findEmailChangeSnapshot(42)).thenThrow(new DataAccessResourceFailureException("secret"));assertThrows(EmailChangeRequestUnavailableException.class,()->reader.loadBinding(actor,session,"new@example.com"));doReturn(Optional.of(new EmailChangeAccountSnapshot(42,null,UserRole.USER,7))).when(users).findEmailChangeSnapshot(42);doThrow(new TransactionSystemException("secret")).when(manager).commit(tx);assertThrows(EmailChangeRequestUnavailableException.class,()->reader.loadBinding(actor,session,"new@example.com"));assertThrows(EmailChangeRequestUnavailableException.class,()->reader.requireTargetAvailable(new EmailChangeBinding(42,null,"new@example.com",7)));}

    @Test void should_reject_isolated_surrogate_in_stored_email() {
        String invalid = String.valueOf((char) 0xd800) + "@example.com";
        when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,invalid,UserRole.USER,7)));
        assertThrows(EmailChangeRequestUnavailableException.class, () -> reader.loadBinding(actor,session,"new@example.com"));
    }
}
