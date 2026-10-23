package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.example.music.security.JwtTokenResult;
import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.LoginTicketContext;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.LoginRequest;
import org.example.music.user.dto.LoginResponse;
import org.example.music.user.dto.LoginStatus;
import org.example.music.user.dto.LoginType;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.exception.LoginRateLimitedException;
import org.example.music.user.exception.RoleSelectionNotAllowedException;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserLoginServiceTest {

    @Mock
    private PasswordLoginService passwordLoginService;

    @Mock
    private EmailCodeLoginService emailCodeLoginService;

    @Mock
    private LoginTicketService loginTicketService;

    @Mock
    private LoginRateLimiter loginRateLimiter;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private UserRepository userRepository;

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
        LoginPrincipal principal = new LoginPrincipal(1, "alice", null, UserRole.USER);
        when(loginRateLimiter.consumeRequest(eq("alice"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(passwordLoginService.authenticate(LoginType.USERNAME_PASSWORD, "alice", "correct-password"))
                .thenReturn(principal);
        when(jwtTokenService.issue(1, UserRole.USER, LoginType.USERNAME_PASSWORD))
                .thenReturn(new JwtTokenResult("jwt", Instant.parse("2026-10-24T00:00:00Z")));

        LoginResponse response = service.login(request, "127.0.0.1");

        assertEquals(LoginStatus.AUTHENTICATED, response.status());
        assertEquals("jwt", response.authentication().accessToken());
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
        LoginPrincipal principal = new LoginPrincipal(2, "admin", "admin@example.com", UserRole.ADMIN);
        when(loginRateLimiter.consumeRequest(eq("admin@example.com"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(passwordLoginService.authenticate(LoginType.EMAIL_PASSWORD, "admin@example.com", "correct-password"))
                .thenReturn(principal);
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
        when(loginRateLimiter.consumeRequest(eq("ticket:ticket"), eq("127.0.0.1")))
                .thenReturn(LoginRateLimitDecision.allowedDecision());
        when(loginTicketService.consume("ticket")).thenReturn(new LoginTicketContext(
                1,
                UserRole.USER,
                LoginType.USERNAME_PASSWORD
        ));
        when(userRepository.findById(1)).thenReturn(Optional.of(new org.example.music.user.domain.UserEntity(
                "alice",
                null,
                "hash",
                UserRole.USER
        )));

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
}