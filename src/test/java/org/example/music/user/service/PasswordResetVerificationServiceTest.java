package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class PasswordResetVerificationServiceTest {
    final PasswordResetConfirmationStore codes=mock(PasswordResetConfirmationStore.class);
    final PasswordResetCodeVerifier verifier=mock(PasswordResetCodeVerifier.class);
    final PasswordResetConfirmationSnapshotReader reader=mock(PasswordResetConfirmationSnapshotReader.class);
    final PasswordResetTicketStore tickets=mock(PasswordResetTicketStore.class);
    final PasswordResetVerificationService service=new PasswordResetVerificationService(codes,verifier,reader,tickets);
    final PasswordResetVerificationRequest request=new PasswordResetVerificationRequest("A".repeat(43),"012345");
    final PasswordResetChallengeSnapshot challenge=new PasswordResetChallengeSnapshot(PasswordResetIdentifiers.digest(request.challengeId()),PasswordResetIdentifiers.digest("a@b.com"),"a@b.com",
            new PasswordResetAdmission(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)),new PasswordResetAccountSnapshot(42,"a@b.com",UserRole.USER,0),"hash",0);
    final PasswordResetTicketPeriod period=new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300));
    @BeforeEach void setup() {
        when(codes.read(anyString())).thenReturn(Optional.of(challenge));when(codes.admitAccount(42)).thenReturn(true);
        when(reader.isCurrent(challenge)).thenReturn(true);when(verifier.matches("012345","hash")).thenReturn(true);
        doThrow(new PasswordResetVerificationFailedException()).when(verifier).reject(anyString());
        when(tickets.readCurrent(42)).thenReturn(Optional.empty());when(tickets.exchange(eq(challenge),eq(Optional.empty()),anyString())).thenReturn(Optional.of(period));
    }
    PasswordResetVerificationResponse verify() { return service.verify(request,"127.0.0.1"); }
    @Test void should_verify_binding_twice_and_exchange_before_returning_secret() {
        var response=verify();assertTrue(PasswordResetIdentifiers.isCanonicalDigest(response.resetTicket()));assertEquals(period.expiresAt(),response.expiresAt());
        var digest=ArgumentCaptor.forClass(String.class);var order=inOrder(codes,verifier,reader,tickets);
        order.verify(codes).admitIp(PasswordResetIdentifiers.digest("127.0.0.1"));order.verify(codes).read(challenge.digest());order.verify(codes).admitAccount(42);
        order.verify(reader).isCurrent(challenge);order.verify(verifier).matches("012345","hash");order.verify(reader).isCurrent(challenge);
        order.verify(tickets).readCurrent(42);order.verify(tickets).exchange(eq(challenge),eq(Optional.empty()),digest.capture());
        assertEquals(PasswordResetIdentifiers.digest(response.resetTicket()),digest.getValue());assertFalse(response.toString().contains(response.resetTicket()));
        org.mockito.Mockito.verify(verifier,never()).reject(anyString());
    }
    @Test void should_use_one_dummy_for_missing_application() {
        when(codes.read(anyString())).thenReturn(Optional.empty());assertThrows(PasswordResetVerificationFailedException.class,this::verify);
        org.mockito.Mockito.verify(verifier).reject("012345");verifyNoInteractions(reader,tickets);org.mockito.Mockito.verify(codes,never()).admitAccount(anyInt());
    }
    @Test void should_hide_account_denial_before_binding_query() {
        when(codes.admitAccount(42)).thenReturn(false);assertThrows(PasswordResetVerificationFailedException.class,this::verify);
        org.mockito.Mockito.verify(verifier).reject("012345");verifyNoInteractions(reader,tickets);
    }
    @Test void should_use_one_dummy_for_first_binding_mismatch() {
        when(reader.isCurrent(challenge)).thenReturn(false);assertThrows(PasswordResetVerificationFailedException.class,this::verify);
        org.mockito.Mockito.verify(verifier).reject("012345");org.mockito.Mockito.verify(verifier,never()).matches(anyString(),anyString());verifyNoInteractions(tickets);
    }
    @Test void should_count_real_wrong_code_without_second_comparison() {
        when(verifier.matches(anyString(),anyString())).thenReturn(false);assertThrows(PasswordResetVerificationFailedException.class,this::verify);
        org.mockito.Mockito.verify(codes).recordFailure(challenge);org.mockito.Mockito.verify(verifier,never()).reject(anyString());verifyNoInteractions(tickets);
    }
    @Test void should_not_add_dummy_after_second_binding_mismatch() {
        when(reader.isCurrent(challenge)).thenReturn(true,false);assertThrows(PasswordResetVerificationFailedException.class,this::verify);
        org.mockito.Mockito.verify(verifier,never()).reject(anyString());verifyNoInteractions(tickets);
    }
    @Test void should_translate_shared_rate_limit_and_keep_retry_seconds() {
        doThrow(new PasswordResetConfirmationRateLimitedException(57)).when(codes).admitIp(anyString());
        assertEquals(57,assertThrows(PasswordResetVerificationRateLimitedException.class,this::verify).retryAfterSeconds());verifyNoInteractions(verifier,reader,tickets);
    }
    @Test void should_translate_shared_unavailable_and_sanitize_cause() {
        when(codes.read(anyString())).thenThrow(new PasswordResetConfirmationUnavailableException(new RuntimeException("private")));
        var failure=assertThrows(PasswordResetVerificationUnavailableException.class,this::verify);assertFalse(failure.getCause().toString().contains("private"));verifyNoInteractions(verifier,reader,tickets);
    }
    @Test void should_not_retry_exchange_conflict_or_unknown_reply() {
        doReturn(Optional.empty()).when(tickets).exchange(any(),any(),anyString());assertThrows(PasswordResetVerificationFailedException.class,this::verify);
        org.mockito.Mockito.verify(tickets,times(1)).exchange(any(),any(),anyString());org.mockito.Mockito.verify(verifier,never()).reject(anyString());
    }
    @Test void should_propagate_ticket_failure_without_old_consume_or_restore() {
        doThrow(new PasswordResetVerificationUnavailableException(null)).when(tickets).exchange(any(),any(),anyString());
        assertThrows(PasswordResetVerificationUnavailableException.class,this::verify);org.mockito.Mockito.verify(tickets,times(1)).exchange(any(),any(),anyString());
        org.mockito.Mockito.verify(verifier,never()).reject(anyString());
    }
    @Test void should_sanitize_unexpected_error() {
        doThrow(new IllegalStateException("private-ticket")).when(tickets).readCurrent(42);
        var failure=assertThrows(IllegalStateException.class,this::verify);assertFalse(failure.getCause().toString().contains("private-ticket"));
    }
    @Test void should_translate_second_binding_read_failure_without_extra_dummy() {
        when(reader.isCurrent(challenge)).thenReturn(true).thenThrow(new PasswordResetConfirmationUnavailableException(null));
        assertThrows(PasswordResetVerificationUnavailableException.class,this::verify);
        org.mockito.Mockito.verify(verifier,times(1)).matches("012345","hash");org.mockito.Mockito.verify(verifier,never()).reject(anyString());verifyNoInteractions(tickets);
    }

}
