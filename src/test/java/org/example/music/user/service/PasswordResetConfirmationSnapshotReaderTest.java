package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.*;

class PasswordResetConfirmationSnapshotReaderTest {
    final UserRepository users=mock(UserRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus tx=mock(TransactionStatus.class);
    static final jakarta.validation.ValidatorFactory VALIDATORS=jakarta.validation.Validation.buildDefaultValidatorFactory();
    final PasswordResetConfirmationRules rules=new PasswordResetConfirmationRules(new PasswordResetEmailRules(VALIDATORS.getValidator()));
    final PasswordResetConfirmationSnapshotReader reader=new PasswordResetConfirmationSnapshotReader(users,manager,rules);
    final PasswordResetAccountSnapshot account=new PasswordResetAccountSnapshot(1,"ALICE@example.com",UserRole.USER,7);
    final String hash="$2a$10$"+"a".repeat(53);
    final PasswordResetChallengeSnapshot challenge=new PasswordResetChallengeSnapshot("A".repeat(43),PasswordResetIdentifiers.digest("alice@example.com"),"alice@example.com",
            new PasswordResetAdmission(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)),account,hash,0);
    @BeforeEach void setup() { when(manager.getTransaction(any())).thenReturn(tx); }
    @AfterAll static void close() { VALIDATORS.close(); }
    @Test void should_preserve_raw_email_and_hash_without_rebinding() {
        var expected=new PasswordResetPreparedSnapshot(1,account.email(),UserRole.USER,7,hash);
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(expected));
        assertEquals(expected,reader.prepareTicket(new PasswordResetTicketSnapshot("A".repeat(43),challenge.emailDigest(),challenge.email(),account,new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)))).orElseThrow()); assertEquals("ALICE@example.com",reader.prepareTicket(new PasswordResetTicketSnapshot("A".repeat(43),challenge.emailDigest(),challenge.email(),account,new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)))).orElseThrow().rawEmail());
    }
    @Test void should_return_empty_for_missing_or_changed_binding() {
        when(users.findPasswordResetSnapshotById(1)).thenReturn(Optional.empty()); assertFalse(reader.isCurrent(challenge));
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(new PasswordResetPreparedSnapshot(1,"alice@example.com",UserRole.USER,7,hash)));
        assertTrue(reader.prepareTicket(new PasswordResetTicketSnapshot("A".repeat(43),challenge.emailDigest(),challenge.email(),account,new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)))).isEmpty());
    }
    @Test void should_reject_blank_hash_or_transaction_start_and_end_failure() {
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(new PasswordResetPreparedSnapshot(1,account.email(),UserRole.USER,7," ")));
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->reader.prepareTicket(new PasswordResetTicketSnapshot("A".repeat(43),challenge.emailDigest(),challenge.email(),account,new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)))));
        doThrow(new CannotCreateTransactionException("secret")).when(manager).getTransaction(any());
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->reader.isCurrent(challenge));
        doReturn(tx).when(manager).getTransaction(any()); when(users.findPasswordResetSnapshotById(1)).thenReturn(Optional.of(account));
        doThrow(new TransactionSystemException("secret")).when(manager).commit(tx);
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->reader.isCurrent(challenge));
    }
    @Test void should_prepare_ticket_only_for_exact_original_binding() {
        var ticket=new PasswordResetTicketSnapshot("A".repeat(43),challenge.emailDigest(),challenge.email(),account,
                new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)));
        var expected=new PasswordResetPreparedSnapshot(1,account.email(),UserRole.USER,7,hash);
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(expected));
        assertEquals(expected,reader.prepareTicket(ticket).orElseThrow());
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(new PasswordResetPreparedSnapshot(1,"alice@example.com",UserRole.USER,7,hash)));
        assertTrue(reader.prepareTicket(ticket).isEmpty());
    }
    @Test void should_reject_invalid_prepared_account_fields() {
        var ticket=new PasswordResetTicketSnapshot("A".repeat(43),challenge.emailDigest(),challenge.email(),account,
                new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)));
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(new PasswordResetPreparedSnapshot(1,account.email(),UserRole.USER,-1,hash)));
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->reader.prepareTicket(ticket));
    }

    @ParameterizedTest @ValueSource(strings={"Legacy-V1", " Legacy-V1 ", "0123456789abcdef0123456789abcdef"})
    void should_preserve_raw_email_and_legacy_hash_without_rebinding(String legacy) {
        var ticket = new PasswordResetTicketSnapshot("A".repeat(43), challenge.emailDigest(), challenge.email(), account,
                new PasswordResetTicketPeriod(Instant.EPOCH, Instant.EPOCH.plusSeconds(300)));
        var expected = new PasswordResetPreparedSnapshot(1, account.email(), UserRole.USER, 7, legacy);
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(Optional.of(expected));
        assertEquals(expected, reader.prepareTicket(ticket).orElseThrow());
        assertEquals(legacy, reader.prepareTicket(ticket).orElseThrow().passwordHash());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t\r\n", "\u2003"})
    void should_reject_null_or_blank_stored_hash_defensively(String value) {
        var ticket = new PasswordResetTicketSnapshot("A".repeat(43), challenge.emailDigest(), challenge.email(), account,
                new PasswordResetTicketPeriod(Instant.EPOCH, Instant.EPOCH.plusSeconds(300)));
        when(users.findPasswordResetPreparedSnapshot(1)).thenReturn(
                Optional.of(new PasswordResetPreparedSnapshot(1, account.email(), UserRole.USER, 7, value)));
        assertThrows(PasswordResetConfirmationUnavailableException.class, () -> reader.prepareTicket(ticket));
    }

}
