package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Optional;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;

class EmailChangeConfirmationSnapshotReaderTest {
    static final jakarta.validation.ValidatorFactory FACTORY=Validation.buildDefaultValidatorFactory();
    final UserRepository users=mock(UserRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus tx=mock(TransactionStatus.class);
    final EmailChangeConfirmationSnapshotReader reader=new EmailChangeConfirmationSnapshotReader(users,manager,new EmailChangeConfirmationRules(FACTORY.getValidator()));
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,Instant.EPOCH);
    final EmailChangeChallengeSnapshot challenge=new EmailChangeChallengeSnapshot(new EmailChangeBinding(42,"old@example.com","new@example.com",7),"A".repeat(43),"hash",Instant.EPOCH,Instant.EPOCH.plusSeconds(300),0);
    @AfterAll static void close(){FACTORY.close();}
    @BeforeEach void setup(){when(manager.getTransaction(any())).thenAnswer(i->{assertTrue(((TransactionDefinition)i.getArgument(0)).isReadOnly());return tx;});when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42," Old@Example.com ",UserRole.USER,7)));when(users.findEmailOwnerId(anyString())).thenReturn(Optional.empty());}
    @Test void should_preserve_raw_email_and_commit_read_only_snapshot(){assertEquals(" Old@Example.com ",reader.prepare(actor,session,challenge).email());verify(manager).commit(tx);}
    @Test void should_revoke_missing_or_changed_authorization(){when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.empty());assertThrows(CurrentUserRevokedException.class,()->reader.load(actor,session));when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,null,UserRole.USER,8)));assertThrows(CurrentUserRevokedException.class,()->reader.load(actor,session));}
    @Test void should_classify_target_owner_and_max_version(){when(users.findEmailOwnerId(anyString())).thenReturn(Optional.of(9));assertThrows(EmailChangeEmailAlreadyExistsException.class,()->reader.prepare(actor,session,challenge));when(users.findEmailOwnerId(anyString())).thenReturn(Optional.of(42));assertThrows(EmailChangeConflictException.class,()->reader.prepare(actor,session,challenge));var max=new AuthenticatedSessionContext(Long.MAX_VALUE,Instant.EPOCH);when(users.findEmailChangeSnapshot(42)).thenReturn(Optional.of(new EmailChangeAccountSnapshot(42,"old@example.com",UserRole.USER,Long.MAX_VALUE)));var c=new EmailChangeChallengeSnapshot(new EmailChangeBinding(42,"old@example.com","new@example.com",Long.MAX_VALUE),challenge.challengeDigest(),"hash",challenge.reservedAt(),challenge.expiresAt(),0);assertThrows(EmailChangeConfirmationUnavailableException.class,()->reader.prepare(actor,max,c));}
    @Test void should_translate_transaction_start_and_completion_failure(){doThrow(new CannotCreateTransactionException("secret")).when(manager).getTransaction(any());assertThrows(EmailChangeConfirmationUnavailableException.class,()->reader.load(actor,session));doReturn(tx).when(manager).getTransaction(any());doThrow(new TransactionSystemException("secret")).when(manager).commit(tx);assertThrows(EmailChangeConfirmationUnavailableException.class,()->reader.prepare(actor,session,challenge));}
}
