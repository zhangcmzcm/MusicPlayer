package org.example.music.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.example.music.common.security.SecurityProblemResponseWriter;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class TokenVersionAuthenticationFilterTest {

    @Mock
    private UserAuthorizationStateService userAuthorizationStateService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void should_replace_authentication_with_database_constrained_login_role() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        authenticate(token("1", "USER", 4L));
        whenSnapshot(new UserAuthorizationSnapshot(1, "alice", null, UserRole.ADMIN, 4L));

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                instanceof AuthenticatedUserPrincipal);
        assertEquals(
                Set.of("ROLE_USER"),
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(authority -> authority.getAuthority())
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void should_reject_when_token_version_is_stale() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(token("1", "USER", 3L));
        whenSnapshot(new UserAuthorizationSnapshot(1, "alice", null, UserRole.USER, 4L));

        filter().doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("TOKEN_REVOKED"));
    }

    @Test
    void should_reject_when_login_role_exceeds_database_maximum_role() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(token("1", "ADMIN", 4L));
        whenSnapshot(new UserAuthorizationSnapshot(1, "alice", null, UserRole.USER, 4L));

        filter().doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("TOKEN_REVOKED"));
    }

    @Test
    void should_reject_when_required_claim_is_missing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(token("1", null, 4L));

        filter().doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_TOKEN"));
    }

    private TokenVersionAuthenticationFilter filter() {
        return new TokenVersionAuthenticationFilter(
                userAuthorizationStateService,
                new SecurityProblemResponseWriter()
        );
    }

    private void whenSnapshot(UserAuthorizationSnapshot snapshot) {
        org.mockito.Mockito.when(userAuthorizationStateService.findSnapshot(snapshot.userId()))
                .thenReturn(Optional.of(snapshot));
    }

    private void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private Jwt token(String subject, String role, long tokenVersion) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .claim("tokenVersion", tokenVersion);
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.build();
    }
}
