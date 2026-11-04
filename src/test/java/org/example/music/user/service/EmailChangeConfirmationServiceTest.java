package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.function.Supplier;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.EmailChangeConfirmationRequest;
import org.example.music.user.exception.*;
import org.example.music.user.repository.EmailChangeConfirmationStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailChangeConfirmationServiceTest {
    final EmailChangeRecentAuthenticationGuard guard=mock(EmailChangeRecentAuthenticationGuard.class);
    final EmailChangeConfirmationStore store=mock(EmailChangeConfirmationStore.class);
    final EmailChangeConfirmationSnapshotReader reader=mock(EmailChangeConfirmationSnapshotReader.class);
    final EmailChangeConfirmationRules rules=mock(EmailChangeConfirmationRules.class);
    final PasswordHasher hasher=mock(PasswordHasher.class);
    final EmailChangeConfirmationTransactionService updater=mock(EmailChangeConfirmationTransactionService.class);
    final EmailChangeConfirmationService service=new EmailChangeConfirmationService(guard,store,reader,rules,hasher,updater);
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,Instant.EPOCH);
    final EmailChangeConfirmationRequest request=new EmailChangeConfirmationRequest("A".repeat(43),"012345");
    final EmailChangeChallengeSnapshot challenge=new EmailChangeChallengeSnapshot(new EmailChangeBinding(42,"old@example.com","new@example.com",7),"digest","hashed",Instant.EPOCH,Instant.EPOCH.plusSeconds(300),0);
    final EmailChangeAccountSnapshot first=new EmailChangeAccountSnapshot(42,"OLD@example.com",UserRole.USER,7);
    final EmailChangeAccountSnapshot prepared=new EmailChangeAccountSnapshot(42," old@example.com ",UserRole.USER,7);
    @BeforeEach void setup(){when(store.read(eq(42),anyString())).thenReturn(challenge);when(reader.load(actor,session)).thenReturn(first);when(hasher.matches("012345","hashed")).thenReturn(true);when(reader.prepare(actor,session,challenge)).thenReturn(prepared);}
    void confirm(){service.confirm(actor,session,()->request);}
    @Test void should_gate_before_parsing_and_not_access_dependencies(){@SuppressWarnings("unchecked") Supplier<EmailChangeConfirmationRequest> input=mock(Supplier.class);doThrow(new ReauthenticationRequiredException()).when(guard).requireRecent(actor,session);assertThrows(ReauthenticationRequiredException.class,()->service.confirm(actor,session,input));verifyNoInteractions(input,store,reader,rules,hasher,updater);}
    @Test void should_consume_before_using_exact_prepared_raw_snapshot(){confirm();var order=inOrder(guard,store,reader,rules,hasher,updater);order.verify(guard).requireRecent(actor,session);order.verify(store).admit(42);order.verify(store).read(eq(42),anyString());order.verify(reader).load(actor,session);order.verify(rules).requireBinding(challenge,first,7);order.verify(hasher).matches("012345","hashed");order.verify(reader).prepare(actor,session,challenge);order.verify(store).consume(challenge);order.verify(updater).update(actor,session,prepared,"new@example.com");verify(reader,times(1)).load(actor,session);}
    @Test void should_count_wrong_code_only_and_not_consume_or_prepare(){when(hasher.matches(anyString(),anyString())).thenReturn(false);assertThrows(EmailChangeVerificationFailedException.class,this::confirm);verify(store).recordFailure(challenge);verify(store,never()).consume(any());verify(reader,never()).prepare(any(),any(),any());verifyNoInteractions(updater);}
    @ParameterizedTest @ValueSource(strings={"admit","read","prepare","consume"})
    void should_stop_on_unconfirmed_operation(String stage){switch(stage){case "admit"->doThrow(new EmailChangeConfirmationUnavailableException(null)).when(store).admit(42);case "read"->when(store.read(eq(42),anyString())).thenThrow(new EmailChangeConfirmationUnavailableException(null));case "prepare"->when(reader.prepare(actor,session,challenge)).thenThrow(new EmailChangeConfirmationUnavailableException(null));case "consume"->doThrow(new EmailChangeConfirmationUnavailableException(null)).when(store).consume(challenge);}assertThrows(EmailChangeConfirmationUnavailableException.class,this::confirm);verifyNoInteractions(updater);}
    @Test void should_not_restore_or_retry_after_database_failure(){doThrow(new EmailChangeConfirmationUnavailableException(null)).when(updater).update(any(),any(),any(),anyString());assertThrows(EmailChangeConfirmationUnavailableException.class,this::confirm);verify(store,times(1)).consume(challenge);verify(updater,times(1)).update(actor,session,prepared,"new@example.com");verify(store).admit(42);verify(store).read(eq(42),anyString());verifyNoMoreInteractions(store);}
    @Test void should_preserve_revocation_conflict_and_old_application_semantics(){doThrow(new EmailChangeVerificationFailedException()).when(rules).requireBinding(challenge,first,7);assertThrows(EmailChangeVerificationFailedException.class,this::confirm);verifyNoInteractions(hasher,updater);}
    @Test void should_sanitize_unexpected_hash_failure(){when(hasher.matches(anyString(),anyString())).thenThrow(new IllegalStateException("secret",new RuntimeException("secret")));var failure=assertThrows(IllegalStateException.class,this::confirm);assertFalse(failure.getCause().getMessage().contains("secret"));assertNull(failure.getCause().getCause());verifyNoInteractions(updater);}
}
