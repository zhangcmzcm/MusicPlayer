package org.example.music.user.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.example.music.security.JwtTokenResult;
import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.LoginTicketContext;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.AuthenticationResponse;
import org.example.music.user.dto.LoginRequest;
import org.example.music.user.dto.LoginResponse;
import org.example.music.user.dto.LoginType;
import org.example.music.user.dto.LoginUserResponse;
import org.example.music.user.dto.RoleSelectionResponse;
import org.example.music.user.exception.InvalidCredentialsException;
import org.example.music.user.exception.LoginRateLimitedException;
import org.example.music.user.exception.LoginTicketInvalidException;
import org.example.music.user.exception.RoleSelectionNotAllowedException;
import org.springframework.stereotype.Service;

@Service
public class UserLoginService {

    private final PasswordLoginService passwordLoginService;
    private final EmailCodeLoginService emailCodeLoginService;
    private final LoginTicketService loginTicketService;
    private final LoginRateLimiter loginRateLimiter;
    private final UserAuthorizationStateService userAuthorizationStateService;
    private final UserLoginActivityService userLoginActivityService;
    private final JwtTokenService jwtTokenService;

    public UserLoginService(
            PasswordLoginService passwordLoginService,
            EmailCodeLoginService emailCodeLoginService,
            LoginTicketService loginTicketService,
            LoginRateLimiter loginRateLimiter,
            UserAuthorizationStateService userAuthorizationStateService,
            UserLoginActivityService userLoginActivityService,
            JwtTokenService jwtTokenService
    ) {
        this.passwordLoginService = passwordLoginService;
        this.emailCodeLoginService = emailCodeLoginService;
        this.loginTicketService = loginTicketService;
        this.loginRateLimiter = loginRateLimiter;
        this.userAuthorizationStateService = userAuthorizationStateService;
        this.userLoginActivityService = userLoginActivityService;
        this.jwtTokenService = jwtTokenService;
    }

    public LoginResponse login(LoginRequest request, String clientIp) {
        String identity = identity(request);
        LoginRateLimitDecision rateLimitDecision = loginRateLimiter.consumeRequest(identity, clientIp);
        if (!rateLimitDecision.allowed()) {
            throw new LoginRateLimitedException(rateLimitDecision.retryAfterSeconds());
        }

        try {
            return switch (request.loginType()) {
                case USERNAME_PASSWORD, EMAIL_PASSWORD -> authenticatePassword(request);
                case EMAIL_CODE -> authenticateEmailCode(request);
                case ROLE_SELECTION -> completeRoleSelection(request);
            };
        } catch (InvalidCredentialsException | LoginTicketInvalidException | RoleSelectionNotAllowedException exception) {
            loginRateLimiter.recordFailure(identity);
            throw exception;
        }
    }

    private LoginResponse authenticatePassword(LoginRequest request) {
        LoginPrincipal principal = passwordLoginService.authenticate(
                request.loginType(),
                request.account(),
                request.password()
        );
        return issueTokenOrRoleSelection(principal, request.loginType());
    }

    private LoginResponse authenticateEmailCode(LoginRequest request) {
        LoginPrincipal principal = emailCodeLoginService.authenticate(request.email(), request.code());
        return issueTokenOrRoleSelection(principal, request.loginType());
    }

    private LoginResponse issueTokenOrRoleSelection(LoginPrincipal principal, LoginType loginType) {
        UserAuthorizationSnapshot snapshot = findMatchingSnapshot(
                principal.id(), principal.maximumRole(), principal.tokenVersion())
                .orElseThrow(InvalidCredentialsException::new);
        if (snapshot.userRole() == UserRole.ADMIN) {
            LoginPrincipal verified = new LoginPrincipal(snapshot.userId(), snapshot.username(), snapshot.email(),
                    snapshot.userRole(), snapshot.tokenVersion());
            return LoginResponse.roleSelectionRequired(new RoleSelectionResponse(
                    loginTicketService.issue(verified, loginType),
                    Instant.now().plusSeconds(300)
            ));
        }

        return completeLogin(snapshot, UserRole.USER, loginType);
    }

    private LoginResponse completeRoleSelection(LoginRequest request) {
        LoginTicketContext context = loginTicketService.consume(request.loginTicket());
        UserAuthorizationSnapshot snapshot = findMatchingSnapshot(
                context.userId(), context.maximumRole(), context.tokenVersion())
                .orElseThrow(LoginTicketInvalidException::new);
        if (!request.loginRole().isAllowedBy(snapshot.userRole())) {
            throw new RoleSelectionNotAllowedException();
        }

        return completeLogin(snapshot, request.loginRole(), context.loginType());
    }

    private LoginResponse completeLogin(
            UserAuthorizationSnapshot snapshot,
            UserRole loginRole,
            LoginType loginType
    ) {
        userLoginActivityService.recordLogin(snapshot.userId());
        JwtTokenResult token = jwtTokenService.issue(snapshot, loginRole, loginType);
        return LoginResponse.authenticated(new AuthenticationResponse(
                token.tokenValue(),
                "Bearer",
                token.expiresAt(),
                new LoginUserResponse(
                        snapshot.userId(),
                        snapshot.username(),
                        snapshot.email(),
                        loginRole
                )
        ));
    }

    private Optional<UserAuthorizationSnapshot> findMatchingSnapshot(
            Integer userId, UserRole expectedRole, long expectedVersion
    ) {
        return userAuthorizationStateService.findSnapshot(userId)
                .filter(snapshot -> snapshot.userRole() == expectedRole && snapshot.tokenVersion() == expectedVersion);
    }

    private String identity(LoginRequest request) {
        return switch (request.loginType()) {
            case ROLE_SELECTION -> "ticket:" + request.loginTicket();
            case EMAIL_CODE -> request.email().trim().toLowerCase(Locale.ROOT);
            case USERNAME_PASSWORD, EMAIL_PASSWORD -> request.account().trim().toLowerCase(Locale.ROOT);
        };
    }
}
