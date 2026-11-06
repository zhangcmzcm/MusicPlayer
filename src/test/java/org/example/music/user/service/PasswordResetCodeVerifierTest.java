package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.PasswordResetConfirmationRules;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;

class PasswordResetCodeVerifierTest {
    @Test void should_reject_even_when_dummy_comparison_returns_true() {
        var hasher=mock(PasswordHasher.class); var rules=mock(PasswordResetConfirmationRules.class);
        when(hasher.hash(anyString())).thenReturn("privateHash");
        var verifier=new PasswordResetCodeVerifier(hasher,rules); clearInvocations(hasher);
        when(hasher.matches("012345","privateHash")).thenReturn(true);
        assertThrows(PasswordResetVerificationFailedException.class,()->verifier.reject("012345"));
        verify(hasher).matches("012345","privateHash"); verifyNoMoreInteractions(hasher);
    }
    @Test void should_sanitize_crypto_failure_without_retry() {
        var hasher=mock(PasswordHasher.class); when(hasher.hash(anyString())).thenReturn("hash");
        var verifier=new PasswordResetCodeVerifier(hasher,mock(PasswordResetConfirmationRules.class)); clearInvocations(hasher);
        when(hasher.matches(anyString(),anyString())).thenThrow(new IllegalStateException("private"));
        var failure=assertThrows(PasswordResetConfirmationUnavailableException.class,()->verifier.reject("012345"));
        assertFalse(failure.getCause().toString().contains("private")); verify(hasher).matches("012345","hash");
    }
    @Test void should_use_same_real_bcrypt_cost_and_allow_concurrent_dummy_rejections() throws Exception {
        try(var validators=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var real=new org.example.music.security.BCryptPasswordHasher(); var hasher=spy(real);
            var rules=new PasswordResetConfirmationRules(new org.example.music.user.domain.PasswordResetEmailRules(validators.getValidator()));
            var verifier=new PasswordResetCodeVerifier(hasher,rules); String producer=real.hash("012345");
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first=pool.submit(()->assertThrows(PasswordResetVerificationFailedException.class,()->verifier.reject("012345")));
                var second=pool.submit(()->assertThrows(PasswordResetVerificationFailedException.class,()->verifier.reject("012345")));
                first.get(5,java.util.concurrent.TimeUnit.SECONDS); second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            }
            var hashes=org.mockito.ArgumentCaptor.forClass(String.class);
            verify(hasher,times(2)).matches(eq("012345"),hashes.capture());
            for(String hash:hashes.getAllValues()) assertEquals(producer.substring(0,7),hash.substring(0,7));
        }
    }

}
