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

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-11-02T12:00:00Z");

    static java.util.stream.Stream<Object> invalidTimes() {
        return java.util.stream.Stream.of(null, "0", "1793620800", true, 0.0, new java.math.BigDecimal("1.0"),
                java.util.List.of(0), java.util.Map.of("time", 0), -1L, Long.MAX_VALUE,
                new java.math.BigInteger("9223372036854775808"), NOW.plusSeconds(1).getEpochSecond());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidTimes")
    void should_reject_present_invalid_time_without_publishing_trusted_context(Object value) throws Exception {
        var jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("1")
                .issuedAt(NOW).claim("role", "USER").claim("tokenVersion", 4L)
                .claims(claims -> claims.put("auth_time", value)).build();
        authenticate(jwt);
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();
        fixedFilter().doFilter(new MockHttpServletRequest(), response, chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_TOKEN"));
        org.junit.jupiter.api.Assertions.assertNull(chain.getRequest());
        assertTrue(AuthenticatedSessionContext.from(SecurityContextHolder.getContext().getAuthentication()).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(userAuthorizationStateService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 1793620800})
    void should_publish_valid_old_or_current_time_and_replace_untrusted_details(long seconds) throws Exception {
        var jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("1").issuedAt(NOW)
                .claim("role", "USER").claim("tokenVersion", 4L).claim("auth_time", seconds).build();
        var auth = new JwtAuthenticationToken(jwt);
        auth.setDetails(new AuthenticatedSessionContext(999, NOW.plusSeconds(500)));
        SecurityContextHolder.getContext().setAuthentication(auth);
        whenSnapshot(new UserAuthorizationSnapshot(1, "alice", null, UserRole.USER, 4));
        var request = new MockHttpServletRequest();
        request.addHeader("X-Auth-Time", "9999999999");
        request.addParameter("auth_time", "9999999999");
        var chain = new MockFilterChain();
        fixedFilter().doFilter(request, new MockHttpServletResponse(), chain);
        assertTrue(chain.getRequest() != null);
        var context = AuthenticatedSessionContext.from(SecurityContextHolder.getContext().getAuthentication()).orElseThrow();
        assertEquals(4, context.tokenVersion());
        assertEquals(java.time.Instant.ofEpochSecond(seconds), context.authenticatedAt());
    }

    @Test
    void should_reject_missing_iat_later_than_iat_and_small_cross_node_clock_skew() throws Exception {
        for (java.time.Instant issuedAt : new java.time.Instant[]{null, NOW.minusSeconds(1), NOW}) {
            var builder = Jwt.withTokenValue("token").header("alg", "HS256").subject("1")
                    .claim("role", "USER").claim("tokenVersion", 4L).claim("auth_time", NOW.getEpochSecond());
            if (issuedAt != null) builder.issuedAt(issuedAt);
            authenticate(builder.build());
            var clock = java.time.Clock.fixed(issuedAt == NOW ? NOW.minusMillis(10) : NOW, java.time.ZoneOffset.UTC);
            var response = new MockHttpServletResponse();
            new TokenVersionAuthenticationFilter(userAuthorizationStateService, new SecurityProblemResponseWriter(), clock)
                    .doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
            assertEquals(401, response.getStatus());
            assertTrue(response.getContentAsString().contains("INVALID_TOKEN"));
        }
    }

    private TokenVersionAuthenticationFilter fixedFilter() {
        return new TokenVersionAuthenticationFilter(userAuthorizationStateService, new SecurityProblemResponseWriter(),
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

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
        org.junit.jupiter.api.Assertions.assertNull(AuthenticatedSessionContext.from(
                SecurityContextHolder.getContext().getAuthentication()).orElseThrow().authenticatedAt());
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
                new SecurityProblemResponseWriter(), java.time.Clock.systemUTC()
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
