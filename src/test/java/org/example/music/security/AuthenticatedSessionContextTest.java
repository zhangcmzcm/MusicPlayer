package org.example.music.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AuthenticatedSessionContextTest {
    @Test
    void should_read_only_typed_details_from_authenticated_actor() {
        var actor = new AuthenticatedUserPrincipal(42, UserRole.USER);
        var auth = new UsernamePasswordAuthenticationToken(actor, null, List.of());
        assertTrue(AuthenticatedSessionContext.from(null).isEmpty());
        assertTrue(AuthenticatedSessionContext.from(auth).isEmpty());
        auth.setDetails("untrusted");
        assertTrue(AuthenticatedSessionContext.from(auth).isEmpty());
        var context = new AuthenticatedSessionContext(7, Instant.EPOCH);
        auth.setDetails(context);
        assertEquals(context, AuthenticatedSessionContext.from(auth).orElseThrow());
        var unauthenticated = new UsernamePasswordAuthenticationToken(actor, null);
        unauthenticated.setDetails(context);
        assertTrue(AuthenticatedSessionContext.from(unauthenticated).isEmpty());
        var wrongPrincipal = new UsernamePasswordAuthenticationToken("42", null, List.of());
        wrongPrincipal.setDetails(context);
        assertTrue(AuthenticatedSessionContext.from(wrongPrincipal).isEmpty());
        auth.setDetails(new AuthenticatedSessionContext(7, null));
        assertNull(AuthenticatedSessionContext.from(auth).orElseThrow().authenticatedAt());
    }
}
