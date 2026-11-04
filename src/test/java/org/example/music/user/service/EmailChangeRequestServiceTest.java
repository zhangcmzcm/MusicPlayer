package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.EmailChangeRequest;
import org.example.music.user.email.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.EmailChangeRequestStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class EmailChangeRequestServiceTest {
    final EmailChangeSnapshotReader snapshots=mock(EmailChangeSnapshotReader.class);
    final EmailChangeRequestStore store=mock(EmailChangeRequestStore.class);
    final SecureVerificationCodeGenerator codes=mock(SecureVerificationCodeGenerator.class);
    final PasswordHasher hasher=mock(PasswordHasher.class);
    final BoundedEmailChangeEmailDelivery delivery=mock(BoundedEmailChangeEmailDelivery.class);
    final Instant now=Instant.parse("2026-11-04T00:00:00Z");
    final AtomicReference<Instant> time=new AtomicReference<>(now);
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time.get();}};
    final EmailChangeRequestService service=new EmailChangeRequestService(new EmailChangeRecentAuthenticationGuard(clock),snapshots,store,codes,hasher,delivery);
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,now.minusSeconds(300));
    final EmailChangeBinding binding=new EmailChangeBinding(42,"old@example.com","new@example.com",7);
    @BeforeEach void setup(){when(snapshots.loadBinding(actor,session,"new@example.com")).thenReturn(binding);when(codes.generate()).thenReturn("٠١٢٣٤٥");when(hasher.hash("012345")).thenReturn("hash");when(store.reserve(any(),anyString(),anyString())).thenAnswer(i->new EmailChangeReservation(i.getArgument(1),now,now.plusSeconds(300),now.plusSeconds(60)));}
    org.example.music.user.dto.EmailChangeRequestResponse request(){return service.request(actor,session,()->new EmailChangeRequest("new@example.com"),"ip");}
    @Test void should_not_parse_or_access_stores_when_auth_time_is_missing(){@SuppressWarnings("unchecked") Supplier<EmailChangeRequest> input=mock(Supplier.class);assertThrows(ReauthenticationRequiredException.class,()->service.request(actor,new AuthenticatedSessionContext(7,null),input,"ip"));verifyNoInteractions(input,snapshots,store,hasher,delivery);}
    @Test void should_keep_admission_when_parsing_and_sending_cross_window(){var response=service.request(actor,session,()->{time.set(now.plusSeconds(1));return new EmailChangeRequest("new@example.com");},"ip");assertEquals(now.plusSeconds(300),response.expiresAt());verify(store).activate(eq(binding),any());}
    @Test void should_follow_order_and_send_ascii_digits_without_exposing_id(){var response=request();assertEquals(32,java.util.Base64.getUrlDecoder().decode(response.challengeId()).length);var envelope=ArgumentCaptor.forClass(EmailChangeMessage.class);var order=inOrder(snapshots,store,hasher,delivery);order.verify(snapshots).loadBinding(actor,session,"new@example.com");order.verify(snapshots).requireTargetAvailable(binding);order.verify(store).reserve(eq(binding),anyString(),anyString());order.verify(hasher).hash("012345");order.verify(store).prepare(eq(binding),any(),eq("hash"));order.verify(delivery).send(envelope.capture());order.verify(snapshots).loadBinding(actor,session,"new@example.com");order.verify(snapshots).requireTargetAvailable(binding);order.verify(store).activate(eq(binding),any());assertEquals("new@example.com",envelope.getValue().recipient());assertEquals("012345",envelope.getValue().code());verify(store,never()).cancel(any(),any());assertFalse(response.toString().contains(response.challengeId()));}
    @Test void should_cancel_on_binding_change_after_sending(){when(snapshots.loadBinding(actor,session,"new@example.com")).thenReturn(binding,new EmailChangeBinding(42,null,"new@example.com",7));assertThrows(EmailChangeRequestConflictException.class,this::request);verify(store).cancel(eq(binding),anyString());verify(store,never()).activate(any(),any());}
    @Test void should_not_reserve_when_target_unavailable(){doThrow(new EmailChangeEmailAlreadyExistsException()).when(snapshots).requireTargetAvailable(binding);assertThrows(EmailChangeEmailAlreadyExistsException.class,this::request);verifyNoInteractions(store,hasher,delivery);}
    @ParameterizedTest @ValueSource(strings={"reserve","prepare","send","recheck","activate"}) void should_cleanup_on_each_failure_without_restoring_active(String step){switch(step){case "reserve"->when(store.reserve(any(),anyString(),anyString())).thenThrow(new EmailChangeRequestUnavailableException(null));case "prepare"->doThrow(new EmailChangeRequestConflictException()).when(store).prepare(any(),any(),anyString());case "send"->doThrow(new EmailChangeSendFailedException(null)).when(delivery).send(any());case "recheck"->when(snapshots.loadBinding(actor,session,"new@example.com")).thenReturn(binding).thenThrow(new CurrentUserRevokedException());case "activate"->doThrow(new EmailChangeRequestUnavailableException(null)).when(store).activate(any(),any());}Class<? extends RuntimeException> expected=switch(step){case "reserve","activate"->EmailChangeRequestUnavailableException.class;case "prepare"->EmailChangeRequestConflictException.class;case "send"->EmailChangeSendFailedException.class;case "recheck"->CurrentUserRevokedException.class;default->throw new AssertionError(step);};assertThrows(expected,this::request);verify(store).cancel(eq(binding),anyString());}
    @Test void should_not_mask_primary_failure_and_sanitize_unknown_hash_failure(){var original=new RuntimeException("secret",new RuntimeException("secret"));original.addSuppressed(new RuntimeException("secret"));when(hasher.hash(anyString())).thenThrow(original);doThrow(new EmailChangeRequestUnavailableException(original)).when(store).cancel(any(),any());var failure=assertThrows(IllegalStateException.class,this::request);assertNull(failure.getCause().getCause());assertEquals(0,failure.getCause().getSuppressed().length);assertFalse(failure.getCause().getMessage().contains("secret"));}
    @ParameterizedTest @ValueSource(strings={"12345","1234567","12a456"}) void should_reject_invalid_generator_output(String code){when(codes.generate()).thenReturn(code);assertThrows(IllegalStateException.class,this::request);verifyNoInteractions(hasher,delivery);verify(store).cancel(any(),anyString());}
}
