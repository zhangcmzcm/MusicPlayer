package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.email.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

class ReauthenticationEmailRequestServiceTest {
    final UserRepository users=mock(UserRepository.class);
    final ReauthenticationEmailRequestStore store=mock(ReauthenticationEmailRequestStore.class);
    final SecureVerificationCodeGenerator codes=mock(SecureVerificationCodeGenerator.class);
    final PasswordHasher hasher=mock(PasswordHasher.class);
    final BoundedReauthenticationEmailDelivery delivery=mock(BoundedReauthenticationEmailDelivery.class);
    final ReauthenticationEmailRequestService service=new ReauthenticationEmailRequestService(users,store,codes,hasher,delivery);
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,null);
    UserAuthorizationSnapshot snapshot(String email,long version,UserRole role){return new UserAuthorizationSnapshot(42,"alice",email,role,version);}
    @BeforeEach void ready() {
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.of(snapshot(" Alice@Example.com ",7,UserRole.USER)));
        when(store.reserve(any(),anyString(),anyString())).thenAnswer(i->new ReauthenticationEmailReservation(i.getArgument(1),Instant.EPOCH,Instant.EPOCH.plusSeconds(300),Instant.EPOCH.plusSeconds(60)));
        when(codes.generate()).thenReturn("٠١٢٣٤٥");when(hasher.hash("012345")).thenReturn("hashed");
    }
    @Test void should_bind_normalized_email_and_version_and_send_ascii_digits_before_activation() {
        var response=service.request(actor,session,"127.0.0.1");
        assertTrue(response.challengeId().matches("[A-Za-z0-9_-]{43}"));assertEquals(Instant.EPOCH.plusSeconds(300),response.expiresAt());
        var envelope=ArgumentCaptor.forClass(ReauthenticationEmailMessage.class);
        var binding=ArgumentCaptor.forClass(ReauthenticationEmailBinding.class);
        var order=inOrder(users,store,hasher,delivery);
        order.verify(users).findAuthorizationSnapshot(42);order.verify(store).reserve(binding.capture(),anyString(),anyString());
        order.verify(hasher).hash("012345");order.verify(store).prepare(any(),any(),eq("hashed"));order.verify(delivery).send(envelope.capture());
        order.verify(users).findAuthorizationSnapshot(42);order.verify(store).activate(any(),any());
        assertEquals("alice@example.com",binding.getValue().email());assertEquals(7,binding.getValue().tokenVersion());assertEquals("012345",envelope.getValue().code());
        verify(store,never()).cancel(any(),any());assertFalse(response.toString().contains(response.challengeId()));
    }
    @Test void should_reject_unbound_email_before_quota() {
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.of(snapshot(null,7,UserRole.USER)));
        assertThrows(CurrentEmailNotBoundException.class,()->service.request(actor,session,"ip"));verifyNoInteractions(store,hasher,delivery);
    }
    @ParameterizedTest @ValueSource(strings={"broken","a\r\n@example.com"})
    void should_fail_closed_on_bad_stored_email(String email) {
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.of(snapshot(email,7,UserRole.USER)));
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->service.request(actor,session,"ip"));verifyNoInteractions(store);
    }
    @Test void should_reject_missing_or_negative_context() {
        assertThrows(InvalidTokenException.class,()->service.request(actor,null,"ip"));
        assertThrows(InvalidTokenException.class,()->service.request(actor,new AuthenticatedSessionContext(-1,null),"ip"));verifyNoInteractions(store);
    }
    @Test void should_revoke_when_account_missing_or_version_changed() {
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.empty());assertThrows(CurrentUserRevokedException.class,()->service.request(actor,session,"ip"));
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.of(snapshot("a@example.com",8,UserRole.USER)));assertThrows(CurrentUserRevokedException.class,()->service.request(actor,session,"ip"));verifyNoInteractions(store);
    }
    @Test void should_revoke_login_role_no_longer_allowed() {
        assertThrows(CurrentUserRevokedException.class,()->service.request(new AuthenticatedUserPrincipal(42,UserRole.ADMIN),session,"ip"));verifyNoInteractions(store);
    }
    @Test void should_cancel_when_email_changes_after_sending() {
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.of(snapshot("a@example.com",7,UserRole.USER)),Optional.of(snapshot("b@example.com",7,UserRole.USER)));
        assertThrows(ReauthenticationEmailRequestConflictException.class,()->service.request(actor,session,"ip"));verify(store).cancel(any(),any());verify(store,never()).activate(any(),any());
    }
    @Test void should_cancel_when_version_changes_after_sending() {
        when(users.findAuthorizationSnapshot(42)).thenReturn(Optional.of(snapshot("a@example.com",7,UserRole.USER)),Optional.of(snapshot("a@example.com",8,UserRole.USER)));
        assertThrows(CurrentUserRevokedException.class,()->service.request(actor,session,"ip"));verify(store).cancel(any(),any());verify(store,never()).activate(any(),any());
    }
    @Test void should_cancel_even_if_reserve_confirmation_is_unknown() {
        when(store.reserve(any(),anyString(),anyString())).thenThrow(new ReauthenticationEmailRequestUnavailableException(null));
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->service.request(actor,session,"ip"));verify(store).cancel(any(),any());verifyNoInteractions(hasher,delivery);
    }
    @Test void should_not_mask_primary_failure_when_cleanup_fails() {
        doThrow(new ReauthenticationEmailSendFailedException(new RuntimeException("secret"))).when(delivery).send(any());
        doThrow(new ReauthenticationEmailRequestUnavailableException(new RuntimeException("secret"))).when(store).cancel(any(),any());
        assertThrows(ReauthenticationEmailSendFailedException.class,()->service.request(actor,session,"ip"));verify(store,never()).activate(any(),any());
    }
    @Test void should_translate_database_failure_without_admission() {
        when(users.findAuthorizationSnapshot(42)).thenThrow(new DataAccessResourceFailureException("secret"));
        assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->service.request(actor,session,"ip"));verifyNoInteractions(store);
    }
    @Test void should_sanitize_unexpected_hash_failure_and_cancel() {
        var original=new RuntimeException("secret-email-code",new RuntimeException("secret-hash"));original.addSuppressed(new RuntimeException("secret-id"));
        when(hasher.hash(anyString())).thenThrow(original);
        var failure=assertThrows(IllegalStateException.class,()->service.request(actor,session,"ip"));assertNull(failure.getCause().getCause());assertEquals(0,failure.getCause().getSuppressed().length);assertFalse(failure.getCause().getMessage().contains("secret"));verify(store).cancel(any(),any());
    }
}
