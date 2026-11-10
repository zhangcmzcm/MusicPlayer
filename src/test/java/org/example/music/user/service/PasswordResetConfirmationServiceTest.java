package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.dto.PasswordResetConfirmationRequest;
import org.example.music.user.exception.*;
import org.example.music.user.repository.*;
import org.junit.jupiter.api.*;

class PasswordResetConfirmationServiceTest {
    final PasswordResetCompletionQuotaStore quota = mock(PasswordResetCompletionQuotaStore.class);
    final PasswordResetTicketStore tickets = mock(PasswordResetTicketStore.class);
    final PasswordResetConfirmationSnapshotReader reader = mock(PasswordResetConfirmationSnapshotReader.class);
    final PasswordResetConfirmationRules rules = mock(PasswordResetConfirmationRules.class);
    final PasswordHasher hasher = mock(PasswordHasher.class);
    final PasswordResetConfirmationTransactionService updater = mock(PasswordResetConfirmationTransactionService.class);
    final PasswordResetConfirmationService service = new PasswordResetConfirmationService(quota, tickets, reader, rules, hasher, updater);
    final PasswordResetConfirmationRequest request = new PasswordResetConfirmationRequest("A".repeat(43), "new-pass");
    final PasswordResetTicketSnapshot ticket = new PasswordResetTicketSnapshot(PasswordResetIdentifiers.digest("A".repeat(43)),
            PasswordResetIdentifiers.digest("a@b.com"), "a@b.com", new PasswordResetAccountSnapshot(1,"a@b.com",UserRole.USER,7),
            new PasswordResetTicketPeriod(Instant.EPOCH, Instant.EPOCH.plusSeconds(300)));
    final PasswordResetPreparedSnapshot prepared = new PasswordResetPreparedSnapshot(1,"a@b.com",UserRole.USER,7,"oldHash");

    @BeforeEach void setup() {
        when(tickets.readByDigest(ticket.digest())).thenReturn(Optional.of(ticket));
        when(quota.admitAccount(1)).thenReturn(true);
        when(reader.prepareTicket(ticket)).thenReturn(Optional.of(prepared));
        when(hasher.hash("new-pass")).thenReturn("newHash");
        when(tickets.consume(ticket)).thenReturn(true);
    }
    void reset() { service.reset(request, "127.0.0.1"); }

    @Test void should_hash_before_consumption_and_write_only_after_explicit_success() {
        reset();
        var order = inOrder(quota, tickets, reader, hasher, rules, updater);
        order.verify(quota).admitIp(PasswordResetIdentifiers.digest("127.0.0.1"));
        order.verify(tickets).readByDigest(ticket.digest());
        order.verify(quota).admitAccount(1);
        order.verify(reader).prepareTicket(ticket);
        order.verify(hasher).matches("new-pass","oldHash");
        order.verify(hasher).hash("new-pass");
        order.verify(rules).requirePasswordHash("newHash");
        order.verify(tickets).consume(ticket);
        order.verify(updater).update(prepared,"newHash");
    }
    @Test void should_reject_missing_ticket_without_password_work() {
        when(tickets.readByDigest(anyString())).thenReturn(Optional.empty());
        assertThrows(PasswordResetVerificationFailedException.class, this::reset);
        verify(quota, never()).admitAccount(anyInt());
        verifyNoInteractions(reader, hasher, updater);
    }
    @Test void should_hide_account_denial_without_password_work() {
        when(quota.admitAccount(1)).thenReturn(false);
        assertThrows(PasswordResetVerificationFailedException.class, this::reset);
        verifyNoInteractions(reader, hasher, updater);
        verify(tickets, never()).consume(any());
    }
    @Test void should_reject_stale_binding_before_password_work() {
        when(reader.prepareTicket(ticket)).thenReturn(Optional.empty());
        assertThrows(PasswordResetVerificationFailedException.class, this::reset);
        verifyNoInteractions(hasher, updater);
        verify(tickets, never()).consume(any());
    }
    @Test void should_stop_on_ip_denial() {
        doThrow(new PasswordResetConfirmationRateLimitedException(60)).when(quota).admitIp(anyString());
        assertThrows(PasswordResetConfirmationRateLimitedException.class, this::reset);
        verifyNoInteractions(tickets, reader, hasher, updater);
    }
    @Test void should_preserve_ticket_when_password_matches() {
        when(hasher.matches("new-pass","oldHash")).thenReturn(true);
        assertThrows(NewPasswordSameAsOldException.class, this::reset);
        verify(hasher, never()).hash(anyString());
        verify(tickets, never()).consume(any());
        verifyNoInteractions(updater);
    }
    @Test void should_preserve_ticket_when_hash_generation_fails() {
        when(hasher.hash(anyString())).thenThrow(new IllegalStateException("private-password"));
        assertThrows(PasswordResetConfirmationUnavailableException.class, this::reset);
        verify(tickets, never()).consume(any());
        verifyNoInteractions(updater);
    }
    @Test void should_preserve_ticket_at_version_upper_bound() {
        when(reader.prepareTicket(ticket)).thenReturn(Optional.of(new PasswordResetPreparedSnapshot(1,"a@b.com",UserRole.USER,Long.MAX_VALUE,"oldHash")));
        assertThrows(PasswordResetConfirmationUnavailableException.class, this::reset);
        verify(tickets, never()).consume(any());
        verify(hasher, never()).hash(anyString());
    }
    @Test void should_never_write_when_consumption_is_unknown_or_false() {
        when(tickets.consume(ticket)).thenThrow(new PasswordResetVerificationUnavailableException(null));
        assertThrows(PasswordResetConfirmationUnavailableException.class, this::reset);
        verifyNoInteractions(updater);
        doReturn(false).when(tickets).consume(ticket);
        assertThrows(PasswordResetVerificationFailedException.class, this::reset);
        verifyNoInteractions(updater);
    }
    @Test void should_not_restore_or_retry_after_database_failure() {
        doThrow(new PasswordResetConfirmationUnavailableException(null)).when(updater).update(any(),anyString());
        assertThrows(PasswordResetConfirmationUnavailableException.class, this::reset);
        verify(tickets, times(1)).consume(ticket);
        verify(updater, times(1)).update(prepared,"newHash");
    }
    @Test void should_preserve_ticket_when_generated_hash_is_invalid() {
        doThrow(new PasswordResetConfirmationUnavailableException(null)).when(rules).requirePasswordHash("newHash");
        assertThrows(PasswordResetConfirmationUnavailableException.class, this::reset);
        verify(tickets, never()).consume(any());
        verifyNoInteractions(updater);
    }
    @Test void should_translate_ticket_read_fault_to_confirmation_family() {
        when(tickets.readByDigest(anyString())).thenThrow(new PasswordResetVerificationUnavailableException(null));
        assertThrows(PasswordResetConfirmationUnavailableException.class, this::reset);
        verifyNoInteractions(reader, hasher, updater);
    }
    @Test void should_sanitize_unexpected_failure_before_logging() {
        doThrow(new IllegalStateException("private-password")).when(updater).update(any(),anyString());
        var failure = assertThrows(IllegalStateException.class, this::reset);
        assertFalse(failure.getCause().toString().contains("private-password"));
        verify(updater, times(1)).update(any(),anyString());
    }
}
