package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.ReauthenticationStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ReauthenticationServiceTest {
    final ReauthenticationSnapshotReader reader=mock(ReauthenticationSnapshotReader.class);
    final ReauthenticationStore store=mock(ReauthenticationStore.class);
    final PasswordHasher hasher=mock(PasswordHasher.class);
    final JwtTokenService tokens=mock(JwtTokenService.class);
    final Clock clock=mock(Clock.class);
    final ReauthenticationService service=new ReauthenticationService(reader,store,hasher,tokens,clock);
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext context=new AuthenticatedSessionContext(7,null);
    final Instant verified=Instant.parse("2026-11-03T12:00:00.500Z");
    final AtomicReference<Instant> now=new AtomicReference<>(verified);
    final ReauthenticationRequest password=new ReauthenticationRequest(ReauthenticationMethod.PASSWORD," old ",null,null);
    final ReauthenticationRequest email=new ReauthenticationRequest(ReauthenticationMethod.EMAIL_CODE,null,"A".repeat(43),"012345");
    UserReauthenticationSnapshot row(String email,String hash,long version,UserRole role){return new UserReauthenticationSnapshot(42,email,hash,role,version);}
    @BeforeEach void ready(){
        when(clock.instant()).thenAnswer(i->now.get());when(reader.load(42)).thenReturn(row(" Alice@Example.com ","hash",7,UserRole.ADMIN));
        when(hasher.matches(anyString(),anyString())).thenReturn(true);
        when(store.read(any(),anyString())).thenAnswer(i->new ReauthenticationChallengeSnapshot(i.getArgument(0),i.getArgument(1),"code-hash",Instant.EPOCH,Instant.EPOCH.plusSeconds(300),0));
        when(tokens.issueReauthenticated(any(),any(),any(),any())).thenAnswer(i->new JwtTokenResult("new-token",now.get().plusSeconds(86400)));
    }
    @Test void should_keep_password_time_before_final_lookup_and_preserve_role_and_version(){
        when(reader.load(42)).thenReturn(row(null,"hash",7,UserRole.ADMIN)).thenAnswer(i->{now.set(verified.plusSeconds(400));return row(null,"hash",7,UserRole.ADMIN);});
        var result=service.reauthenticate(actor,context,password);assertEquals(verified.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),result.authenticatedAt());assertEquals("Bearer",result.tokenType());
        verify(tokens).issueReauthenticated(eq(new JwtTokenSubject(42,UserRole.ADMIN,7)),eq(UserRole.USER),eq(ReauthenticationMethod.PASSWORD),eq(result.authenticatedAt()));
        verify(hasher).matches(" old ","hash");verify(hasher,never()).hash(anyString());verify(store).admit(42);verify(store,never()).read(any(),any());verify(store,never()).consume(any());
        verify(tokens,never()).issueLegacyWithoutAuthTime(any(),any(),any());
    }
    @Test void should_capture_email_time_before_consumption_and_final_lookup(){
        doAnswer(i->{now.set(verified.plusSeconds(100));return null;}).when(store).consume(any());
        when(reader.load(42)).thenReturn(row(" Alice@Example.com ","hash",7,UserRole.USER)).thenAnswer(i->{now.set(verified.plusSeconds(200));return row("alice@example.com","other-hash",7,UserRole.USER);});
        var result=service.reauthenticate(actor,context,email);assertEquals(verified.getEpochSecond(),result.authenticatedAt().getEpochSecond());
        var order=inOrder(store,reader,hasher,clock,tokens);order.verify(store).admit(42);order.verify(reader).load(42);order.verify(store).read(eq(new ReauthenticationEmailBinding(42,"alice@example.com",7)),anyString());order.verify(hasher).matches("012345","code-hash");order.verify(clock).instant();order.verify(store).consume(any());order.verify(reader).load(42);order.verify(tokens).issueReauthenticated(any(),eq(UserRole.USER),eq(ReauthenticationMethod.EMAIL_CODE),eq(result.authenticatedAt()));
    }
    @Test void should_reject_missing_or_invalid_trusted_context_before_admission(){
        assertThrows(InvalidTokenException.class,()->service.reauthenticate(actor,null,password));assertThrows(InvalidTokenException.class,()->service.reauthenticate(actor,new AuthenticatedSessionContext(-1,null),password));assertThrows(InvalidTokenException.class,()->service.reauthenticate(null,context,password));verifyNoInteractions(store,reader,hasher,tokens);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void should_stop_after_quota_denial_or_unavailability(boolean limited){
        doThrow(limited?new ReauthenticationRateLimitedException(60):new ReauthenticationUnavailableException(null)).when(store).admit(42);
        if(limited)assertThrows(ReauthenticationRateLimitedException.class,()->service.reauthenticate(actor,context,password));
        else assertThrows(ReauthenticationUnavailableException.class,()->service.reauthenticate(actor,context,password));verifyNoInteractions(reader,hasher,tokens);
    }
    @Test void should_revoke_initial_version_or_role_mismatch(){
        when(reader.load(42)).thenReturn(row(null,"hash",8,UserRole.USER));assertThrows(CurrentUserRevokedException.class,()->service.reauthenticate(actor,context,password));
        when(reader.load(42)).thenReturn(row(null,"hash",7,UserRole.USER));assertThrows(CurrentUserRevokedException.class,()->service.reauthenticate(new AuthenticatedUserPrincipal(42,UserRole.ADMIN),context,password));verifyNoInteractions(hasher,tokens);
    }
    @Test void should_fail_wrong_password_without_final_lookup_or_signing(){
        when(hasher.matches(anyString(),anyString())).thenReturn(false);assertThrows(ReauthenticationFailedException.class,()->service.reauthenticate(actor,context,password));verify(reader,times(1)).load(42);verifyNoInteractions(tokens);verify(store,never()).recordFailure(any());
    }
    @Test void should_record_wrong_code_then_return_generic_failure(){
        when(hasher.matches(anyString(),anyString())).thenReturn(false);assertThrows(ReauthenticationFailedException.class,()->service.reauthenticate(actor,context,email));verify(store).recordFailure(any());verify(store,never()).consume(any());verifyNoInteractions(tokens);
    }
    @Test void should_not_mask_unconfirmed_failed_attempt_as_wrong_code(){
        when(hasher.matches(anyString(),anyString())).thenReturn(false);doThrow(new ReauthenticationUnavailableException(null)).when(store).recordFailure(any());assertThrows(ReauthenticationUnavailableException.class,()->service.reauthenticate(actor,context,email));verifyNoInteractions(tokens);
    }
    @Test void should_reject_missing_or_invalid_initial_email_after_admission(){
        when(reader.load(42)).thenReturn(row(null,"hash",7,UserRole.USER));assertThrows(CurrentEmailNotBoundException.class,()->service.reauthenticate(actor,context,email));verify(store).admit(42);
        when(reader.load(42)).thenReturn(row("broken","hash",7,UserRole.USER));assertThrows(ReauthenticationUnavailableException.class,()->service.reauthenticate(actor,context,email));verifyNoInteractions(hasher,tokens);
    }
    @ParameterizedTest @ValueSource(strings={"hash","version","role","email","unbound","deleted"})
    void should_recheck_binding_after_verification(String change){
        var initial=row("alice@example.com","hash",7,UserRole.ADMIN);
        var last=switch(change){case "hash"->row("alice@example.com","new-hash",7,UserRole.ADMIN);case "version"->row("alice@example.com","hash",8,UserRole.ADMIN);case "role"->row("alice@example.com","hash",7,UserRole.USER);case "email"->row("other@example.com","hash",7,UserRole.ADMIN);case "unbound"->row(null,"hash",7,UserRole.ADMIN);default->initial;};
        when(reader.load(42)).thenReturn(initial).thenAnswer(i->{if(change.equals("deleted"))throw new CurrentUserRevokedException();return last;});
        var acting=new AuthenticatedUserPrincipal(42,UserRole.ADMIN);boolean pwd=change.equals("hash");
        if(change.equals("hash")||change.equals("email")||change.equals("unbound"))assertThrows(ReauthenticationFailedException.class,()->service.reauthenticate(acting,context,pwd?password:email));
        else assertThrows(CurrentUserRevokedException.class,()->service.reauthenticate(acting,context,email));verifyNoInteractions(tokens);
    }
    @Test void should_not_sign_after_unconfirmed_consumption(){
        doThrow(new ReauthenticationUnavailableException(null)).when(store).consume(any());assertThrows(ReauthenticationUnavailableException.class,()->service.reauthenticate(actor,context,email));verify(reader,times(1)).load(42);verifyNoInteractions(tokens);
    }
    @Test void should_sanitize_unexpected_hash_and_signing_failure_without_recovery(){
        var secret=new IllegalStateException("secret-email-code-token",new RuntimeException("secret-hash"));secret.addSuppressed(new RuntimeException("secret-id"));when(tokens.issueReauthenticated(any(),any(),any(),any())).thenThrow(secret);
        var failure=assertThrows(IllegalStateException.class,()->service.reauthenticate(actor,context,email));assertFalse(failure.getMessage().contains("secret"));assertFalse(failure.getCause().getMessage().contains("secret"));assertNull(failure.getCause().getCause());assertEquals(0,failure.getCause().getSuppressed().length);verify(store).consume(any());
    }
}
