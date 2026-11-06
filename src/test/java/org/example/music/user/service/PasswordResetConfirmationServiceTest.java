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
import org.example.music.user.repository.PasswordResetConfirmationStore;
import org.junit.jupiter.api.*;

class PasswordResetConfirmationServiceTest {
    final PasswordResetConfirmationStore store=mock(PasswordResetConfirmationStore.class);
    final PasswordResetConfirmationSnapshotReader reader=mock(PasswordResetConfirmationSnapshotReader.class);
    final PasswordResetConfirmationRules rules=mock(PasswordResetConfirmationRules.class);
    final PasswordResetCodeVerifier verifier=mock(PasswordResetCodeVerifier.class);
    final PasswordHasher hasher=mock(PasswordHasher.class);
    final PasswordResetConfirmationTransactionService updater=mock(PasswordResetConfirmationTransactionService.class);
    final PasswordResetConfirmationService service=new PasswordResetConfirmationService(store,reader,rules,verifier,hasher,updater);
    final PasswordResetConfirmationRequest request=new PasswordResetConfirmationRequest("A".repeat(43),"012345","new-pass");
    final PasswordResetChallengeSnapshot challenge=new PasswordResetChallengeSnapshot(PasswordResetIdentifiers.digest(request.challengeId()),PasswordResetIdentifiers.digest("a@b.com"),"a@b.com",
            new PasswordResetAdmission(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)),new PasswordResetAccountSnapshot(1,"a@b.com",UserRole.USER,7),"codeHash",0);
    final PasswordResetPreparedSnapshot prepared=new PasswordResetPreparedSnapshot(1,"a@b.com",UserRole.USER,7,"oldHash");
    @BeforeEach void setup() {
        when(store.read(anyString())).thenReturn(Optional.of(challenge)); when(store.admitAccount(1)).thenReturn(true);
        when(reader.isCurrent(challenge)).thenReturn(true); when(reader.prepare(challenge)).thenReturn(Optional.of(prepared));
        when(verifier.matches(request.code(),"codeHash")).thenReturn(true); when(hasher.hash("new-pass")).thenReturn("newHash");
        when(store.consume(challenge)).thenReturn(true);
        doThrow(new PasswordResetVerificationFailedException()).when(verifier).reject(anyString());
    }
    void reset() { service.reset(request,"127.0.0.1"); }
    @Test void should_hash_before_consume_and_update_only_after_explicit_success() {
        reset(); var order=inOrder(store,reader,verifier,hasher,rules,updater);
        order.verify(store).admitIp(PasswordResetIdentifiers.digest("127.0.0.1")); order.verify(store).read(challenge.digest());
        order.verify(store).admitAccount(1); order.verify(reader).isCurrent(challenge); order.verify(verifier).matches(request.code(),"codeHash");
        order.verify(reader).prepare(challenge); order.verify(hasher).matches("new-pass","oldHash"); order.verify(hasher).hash("new-pass");
        order.verify(rules).requirePasswordHash("newHash"); order.verify(store).consume(challenge); order.verify(updater).update(prepared,"newHash");
        verify(verifier,never()).reject(anyString());
    }
    @Test void should_use_one_dummy_comparison_for_absent_challenge() {
        when(store.read(anyString())).thenReturn(Optional.empty());
        assertThrows(PasswordResetVerificationFailedException.class,this::reset); verify(verifier).reject("012345");
        verify(store,never()).admitAccount(anyInt()); verifyNoInteractions(reader,hasher,updater);
    }
    @Test void should_hide_account_limit_and_avoid_binding_query_or_consumption() {
        when(store.admitAccount(1)).thenReturn(false);
        assertThrows(PasswordResetVerificationFailedException.class,this::reset); verify(verifier).reject("012345");
        verifyNoInteractions(reader,hasher,updater); verify(store,never()).recordFailure(any()); verify(store,never()).consume(any());
    }
    @Test void should_use_one_dummy_for_stale_binding() {
        when(reader.isCurrent(challenge)).thenReturn(false); assertThrows(PasswordResetVerificationFailedException.class,this::reset);
        verify(verifier).reject("012345"); verify(verifier,never()).matches(anyString(),anyString()); verifyNoInteractions(updater);
    }
    @Test void should_count_wrong_code_without_second_comparison() {
        when(verifier.matches(anyString(),anyString())).thenReturn(false); assertThrows(PasswordResetVerificationFailedException.class,this::reset);
        verify(store).recordFailure(challenge); verify(verifier,never()).reject(anyString()); verifyNoInteractions(hasher,updater);
    }
    @Test void should_not_compare_when_ip_denied_or_store_unknown() {
        doThrow(new PasswordResetConfirmationRateLimitedException(60)).when(store).admitIp(anyString());
        assertThrows(PasswordResetConfirmationRateLimitedException.class,this::reset); verifyNoInteractions(verifier,hasher,reader,updater);
    }
    @Test void should_keep_code_when_password_is_equivalent() {
        when(hasher.matches("new-pass","oldHash")).thenReturn(true);
        assertThrows(NewPasswordSameAsOldException.class,this::reset); verify(hasher,never()).hash(anyString());
        verify(store,never()).consume(any()); verify(store,never()).recordFailure(any()); verifyNoInteractions(updater);
    }
    @Test void should_keep_code_on_hash_failure_or_invalid_hash() {
        when(hasher.hash(anyString())).thenThrow(new IllegalStateException("secret"));
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::reset); verify(store,never()).consume(any()); verifyNoInteractions(updater);
    }
    @Test void should_keep_code_on_max_version() {
        when(reader.prepare(challenge)).thenReturn(Optional.of(new PasswordResetPreparedSnapshot(1,"a@b.com",UserRole.USER,Long.MAX_VALUE,"oldHash")));
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::reset); verify(store,never()).consume(any()); verify(hasher,never()).hash(anyString());
    }
    @Test void should_never_start_write_for_unknown_or_false_consumption() {
        when(store.consume(challenge)).thenThrow(new PasswordResetConfirmationUnavailableException(null));
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::reset); verifyNoInteractions(updater);
        doReturn(false).when(store).consume(challenge); assertThrows(PasswordResetVerificationFailedException.class,this::reset); verifyNoInteractions(updater);
    }
    @Test void should_not_restore_or_retry_after_update_failure() {
        doThrow(new PasswordResetConfirmationUnavailableException(null)).when(updater).update(any(),anyString());
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::reset);
        verify(store,times(1)).consume(challenge); verify(updater,times(1)).update(prepared,"newHash"); verify(verifier,never()).reject(anyString());
    }
    @Test void should_keep_code_on_invalid_generated_hash() {
        doThrow(new PasswordResetConfirmationUnavailableException(null)).when(rules).requirePasswordHash("newHash");
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::reset); verify(store,never()).consume(any()); verifyNoInteractions(updater);
    }
    @Test void should_not_add_dummy_after_correct_code_when_prepare_binding_changed() {
        when(reader.prepare(challenge)).thenReturn(Optional.empty()); assertThrows(PasswordResetVerificationFailedException.class,this::reset);
        verify(verifier,never()).reject(anyString()); verify(store,never()).consume(any()); verifyNoInteractions(updater);
    }
    @Test void should_not_add_dummy_when_read_is_unknown() {
        when(store.read(anyString())).thenThrow(new PasswordResetConfirmationUnavailableException(null));
        assertThrows(PasswordResetConfirmationUnavailableException.class,this::reset); verifyNoInteractions(verifier,reader,hasher,updater);
    }

    @Test void should_sanitize_unexpected_failure_before_global_logging() {
        doThrow(new IllegalStateException("private-password")).when(updater).update(any(),anyString());
        var failure=assertThrows(IllegalStateException.class,this::reset);
        assertFalse(failure.getCause().toString().contains("private-password"));
        verify(updater,times(1)).update(any(),anyString());
    }

}
