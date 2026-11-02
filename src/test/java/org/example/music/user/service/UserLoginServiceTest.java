package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.example.music.security.JwtTokenResult;
import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.LoginTicketContext;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginRequest;
import org.example.music.user.dto.LoginResponse;
import org.example.music.user.dto.LoginStatus;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.exception.LoginRateLimitedException;
import org.example.music.user.exception.RoleSelectionNotAllowedException;
import org.example.music.user.exception.LoginTicketInvalidException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserLoginServiceTest {

    @org.junit.jupiter.api.AfterEach
    void should_never_use_legacy_signing_for_interactive_login() {
        verify(jwtTokenService, never()).issueLegacyWithoutAuthTime(any(), any(), any());
    }

    @Test
    void should_preserve_ticket_authentication_time_when_clock_advances() {
        var verified = Instant.parse("2026-11-02T12:00:00Z");
        org.mockito.Mockito.doReturn(verified.plusSeconds(600)).when(clock).instant();
        assertEquals(verified.plusSeconds(600), clock.instant());
        var request = new LoginRequest(LoginType.ROLE_SELECTION, null, null, null, null, "ticket", UserRole.USER);
        allow(request);
        when(loginTicketService.consume("ticket")).thenReturn(
                new LoginTicketContext(1, UserRole.ADMIN, LoginType.EMAIL_CODE, 7, verified));
        var snapshot = new UserAuthorizationSnapshot(1, "alice", null, UserRole.ADMIN, 7);
        when(userAuthorizationStateService.findSnapshot(1)).thenReturn(Optional.of(snapshot));
        when(jwtTokenService.issue(snapshot, UserRole.USER, LoginType.EMAIL_CODE, verified))
                .thenReturn(new JwtTokenResult("jwt", verified.plusSeconds(90000)));
        assertEquals(LoginStatus.AUTHENTICATED, service.login(request, "127.0.0.1").status());
        verify(jwtTokenService).issue(snapshot, UserRole.USER, LoginType.EMAIL_CODE, verified);
    }

    @Mock
    private PasswordLoginService passwordLoginService;

    @Mock
    private EmailCodeLoginService emailCodeLoginService;

    @Mock
    private LoginTicketService loginTicketService;

    @Mock
    private LoginRateLimiter loginRateLimiter;

    @Mock
    private UserAuthorizationStateService userAuthorizationStateService;

    @Mock
    private UserLoginActivityService userLoginActivityService;

    @Mock
    private JwtTokenService jwtTokenService;

    @org.mockito.Spy
    private java.time.Clock clock = java.time.Clock.systemUTC();

    @InjectMocks
    private UserLoginService service;

    @Test
    void should_issue_token_when_regular_user_password_login_succeeds() {
        LoginRequest request = new LoginRequest(
                LoginType.USERNAME_PASSWORD,
                "alice",
                "correct-password",
                null,
                null,
                null,
                null
        );
        LoginPrincipal principal = new LoginPrincipal(1, "alice", null, UserRole.USER, 0L, java.time.Instant.EPOCH);
        UserAuthorizationSnapshot snapshot = new UserAuthorizationSnapshot(
                1,
                "alice",
                null,
                UserRole.USER,
                0L
        );
        when(loginRateLimiter.consumeRequest(eq("alice"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(passwordLoginService.authenticate(LoginType.USERNAME_PASSWORD, "alice", "correct-password"))
                .thenReturn(principal);
        when(userAuthorizationStateService.findSnapshot(1)).thenReturn(Optional.of(snapshot));
        when(jwtTokenService.issue(snapshot, UserRole.USER, LoginType.USERNAME_PASSWORD, java.time.Instant.EPOCH))
                .thenReturn(new JwtTokenResult("jwt", Instant.parse("2026-10-24T00:00:00Z")));

        LoginResponse response = service.login(request, "127.0.0.1");

        assertEquals(LoginStatus.AUTHENTICATED, response.status());
        assertEquals("jwt", response.authentication().accessToken());
        verify(userLoginActivityService).recordLogin(1);
    }

    @Test
    void should_require_role_selection_when_admin_password_login_succeeds() {
        LoginRequest request = new LoginRequest(
                LoginType.EMAIL_PASSWORD,
                "admin@example.com",
                "correct-password",
                null,
                null,
                null,
                null
        );
        LoginPrincipal principal = new LoginPrincipal(2, "admin", "admin@example.com", UserRole.ADMIN, 0L, java.time.Instant.EPOCH);
        when(loginRateLimiter.consumeRequest(eq("admin@example.com"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(passwordLoginService.authenticate(LoginType.EMAIL_PASSWORD, "admin@example.com", "correct-password"))
                .thenReturn(principal);
        when(userAuthorizationStateService.findSnapshot(2)).thenReturn(Optional.of(
                new UserAuthorizationSnapshot(2, "admin", "admin@example.com", UserRole.ADMIN, 0L)));
        when(loginTicketService.issue(principal, LoginType.EMAIL_PASSWORD)).thenReturn("ticket");

        LoginResponse response = service.login(request, "127.0.0.1");

        assertEquals(LoginStatus.ROLE_SELECTION_REQUIRED, response.status());
        assertEquals("ticket", response.roleSelection().loginTicket());
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void should_reject_role_selection_for_regular_user() {
        LoginRequest request = new LoginRequest(
                LoginType.ROLE_SELECTION,
                null,
                null,
                null,
                null,
                "ticket",
                UserRole.ADMIN
        );
        UserAuthorizationSnapshot snapshot = new UserAuthorizationSnapshot(
                1,
                "alice",
                null,
                UserRole.USER,
                0L
        );
        when(loginRateLimiter.consumeRequest(eq("ticket:ticket"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(loginTicketService.consume("ticket")).thenReturn(new LoginTicketContext(
                1,
                UserRole.USER,
                LoginType.USERNAME_PASSWORD,
                0L, java.time.Instant.EPOCH));
        when(userAuthorizationStateService.findSnapshot(1)).thenReturn(Optional.of(snapshot));

        assertThrows(RoleSelectionNotAllowedException.class, () -> service.login(request, "127.0.0.1"));
    }

    @Test
    void should_record_failed_attempt_when_credentials_are_invalid() {
        LoginRequest request = new LoginRequest(
                LoginType.USERNAME_PASSWORD,
                "alice",
                "wrong-password",
                null,
                null,
                null,
                null
        );
        when(loginRateLimiter.consumeRequest(eq("alice"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(passwordLoginService.authenticate(LoginType.USERNAME_PASSWORD, "alice", "wrong-password"))
                .thenThrow(new InvalidCredentialsException());

        assertThrows(InvalidCredentialsException.class, () -> service.login(request, "127.0.0.1"));

        verify(loginRateLimiter).recordFailure("alice");
    }

    @Test
    void should_reject_when_login_rate_limit_is_exhausted() {
        LoginRequest request = new LoginRequest(
                LoginType.USERNAME_PASSWORD,
                "alice",
                "correct-password",
                null,
                null,
                null,
                null
        );
        when(loginRateLimiter.consumeRequest(eq("alice"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.limitedDecision(LoginRateLimitScope.IP_RATE_LIMIT, 30));

        LoginRateLimitedException exception = assertThrows(
                LoginRateLimitedException.class,
                () -> service.login(request, "127.0.0.1")
        );

        assertEquals(30, exception.getRetryAfterSeconds());
        verifyNoInteractions(passwordLoginService);
    }

    static Stream<Arguments> staleInitialStates() {
        return Stream.of(LoginType.USERNAME_PASSWORD, LoginType.EMAIL_PASSWORD, LoginType.EMAIL_CODE)
                .flatMap(type -> Stream.of(UserRole.USER, UserRole.ADMIN)
                        .flatMap(role -> Stream.of("missing", "version", "role")
                                .map(failure -> Arguments.of(type, role, failure))));
    }

    @ParameterizedTest
    @MethodSource("staleInitialStates")
    void should_reject_stale_initial_verification_before_either_issuance_branch(
            LoginType type, UserRole role, String failure
    ) {
        LoginRequest request = initialRequest(type);
        LoginPrincipal principal = new LoginPrincipal(1, "alice", "alice@example.com", role, 7L, java.time.Instant.EPOCH);
        allow(request);
        if (type == LoginType.EMAIL_CODE) {
            when(emailCodeLoginService.authenticate(request.email(), request.code())).thenReturn(principal);
        } else {
            when(passwordLoginService.authenticate(type, request.account(), request.password())).thenReturn(principal);
        }
        Optional<UserAuthorizationSnapshot> snapshot = failure.equals("missing") ? Optional.empty()
                : Optional.of(new UserAuthorizationSnapshot(1, "alice", "alice@example.com",
                        failure.equals("role") ? (role == UserRole.USER ? UserRole.ADMIN : UserRole.USER) : role,
                        failure.equals("version") ? 8L : 7L));
        when(userAuthorizationStateService.findSnapshot(1)).thenReturn(snapshot);
        assertThrows(InvalidCredentialsException.class, () -> service.login(request, "127.0.0.1"));
        verifyNoInteractions(jwtTokenService, loginTicketService, userLoginActivityService);
        verify(loginRateLimiter).recordFailure(identity(request));
        verify(userAuthorizationStateService, times(1)).findSnapshot(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "version", "role"})
    void should_reject_invalid_ticket_snapshot_without_signing(String failure) {
        LoginRequest request = new LoginRequest(LoginType.ROLE_SELECTION, null, null, null, null, "ticket", UserRole.USER);
        allow(request);
        when(loginTicketService.consume("ticket")).thenReturn(new LoginTicketContext(1, UserRole.ADMIN, LoginType.EMAIL_PASSWORD, 7L, java.time.Instant.EPOCH));
        when(userAuthorizationStateService.findSnapshot(1)).thenReturn(failure.equals("missing") ? Optional.empty()
                : Optional.of(new UserAuthorizationSnapshot(1, "admin", null,
                        failure.equals("role") ? UserRole.USER : UserRole.ADMIN, failure.equals("version") ? 8L : 7L)));
        assertThrows(LoginTicketInvalidException.class, () -> service.login(request, "127.0.0.1"));
        verifyNoInteractions(jwtTokenService, userLoginActivityService);
        verify(loginRateLimiter).recordFailure("ticket:ticket");
    }

    @Test
    void should_sign_only_the_matched_immutable_snapshot_without_later_refresh() {
        LoginRequest request = initialRequest(LoginType.USERNAME_PASSWORD);
        LoginPrincipal principal = new LoginPrincipal(1, "old_name", null, UserRole.USER, 7L, java.time.Instant.EPOCH);
        UserAuthorizationSnapshot matched = new UserAuthorizationSnapshot(1, "new_name", "alice@example.com", UserRole.USER, 7L);
        allow(request);
        when(passwordLoginService.authenticate(request.loginType(), request.account(), request.password())).thenReturn(principal);
        when(userAuthorizationStateService.findSnapshot(1)).thenReturn(Optional.of(matched),
                Optional.of(new UserAuthorizationSnapshot(1, "new_name", "alice@example.com", UserRole.USER, 8L)));
        when(jwtTokenService.issue(matched, UserRole.USER, LoginType.USERNAME_PASSWORD, java.time.Instant.EPOCH))
                .thenReturn(new JwtTokenResult("jwt", Instant.now().plusSeconds(600)));
        assertEquals("new_name", service.login(request, "127.0.0.1").authentication().user().username());
        verify(userAuthorizationStateService, times(1)).findSnapshot(1);
        verify(jwtTokenService).issue(org.mockito.ArgumentMatchers.same(matched), eq(UserRole.USER), eq(LoginType.USERNAME_PASSWORD), eq(java.time.Instant.EPOCH));
        verify(loginRateLimiter, never()).recordFailure(any());
    }

    private LoginRequest initialRequest(LoginType type) {
        return switch (type) {
            case USERNAME_PASSWORD -> new LoginRequest(type, "alice", "correct-password", null, null, null, null);
            case EMAIL_PASSWORD -> new LoginRequest(type, "alice@example.com", "correct-password", null, null, null, null);
            case EMAIL_CODE -> new LoginRequest(type, null, null, "alice@example.com", "123456", null, null);
            default -> throw new IllegalArgumentException();
        };
    }

    private String identity(LoginRequest request) {
        return request.loginType() == LoginType.ROLE_SELECTION ? "ticket:" + request.loginTicket()
                : request.loginType() == LoginType.EMAIL_CODE ? request.email() : request.account();
    }

    private void allow(LoginRequest request) {
        when(loginRateLimiter.consumeRequest(identity(request), "127.0.0.1"))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
    }
}
