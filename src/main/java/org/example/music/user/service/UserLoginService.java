package org.example.music.user.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.example.music.security.JwtTokenResult;
import org.example.music.security.JwtTokenService;
import org.example.music.user.domain.LoginPrincipal;
import org.example.music.user.domain.LoginTicketContext;
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
import org.example.music.user.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserLoginService {

    private final PasswordLoginService passwordLoginService;
    private final EmailCodeLoginService emailCodeLoginService;
    private final LoginTicketService loginTicketService;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;

    public UserLoginService(
            PasswordLoginService passwordLoginService,
            EmailCodeLoginService emailCodeLoginService,
            LoginTicketService loginTicketService,
            LoginRateLimiter loginRateLimiter,
            JwtTokenService jwtTokenService,
            UserRepository userRepository
    ) {
        this.passwordLoginService = passwordLoginService;
        this.emailCodeLoginService = emailCodeLoginService;
        this.loginTicketService = loginTicketService;
        this.loginRateLimiter = loginRateLimiter;
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
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
        if (principal.maximumRole() == UserRole.ADMIN) {
            return LoginResponse.roleSelectionRequired(new RoleSelectionResponse(
                    loginTicketService.issue(principal, loginType),
                    java.time.Instant.now().plusSeconds(300)
            ));
        }
        return completeLogin(principal, UserRole.USER, loginType);
    }

    private LoginResponse completeRoleSelection(LoginRequest request) {
        LoginTicketContext context = loginTicketService.consume(request.loginTicket());
        LoginPrincipal principal = userRepository.findById(context.userId())
                .map(this::principal)
                .orElseThrow(LoginTicketInvalidException::new);

        if (principal.maximumRole() != context.maximumRole()) {
            throw new LoginTicketInvalidException();
        }
        if (request.loginRole() == UserRole.ADMIN && principal.maximumRole() != UserRole.ADMIN) {
            throw new RoleSelectionNotAllowedException();
        }

        return completeLogin(principal, request.loginRole(), context.loginType());
    }

    private LoginResponse completeLogin(
            LoginPrincipal principal,
            UserRole loginRole,
            LoginType loginType
    ) {
        userRepository.findById(principal.id()).ifPresent(user -> {
            user.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
            userRepository.save(user);
        });
        JwtTokenResult token = jwtTokenService.issue(principal.id(), loginRole, loginType);
        return LoginResponse.authenticated(new AuthenticationResponse(
                token.tokenValue(),
                "Bearer",
                token.expiresAt(),
                new LoginUserResponse(
                        principal.id(),
                        principal.username(),
                        principal.email(),
                        loginRole
                )
        ));
    }

    private String identity(LoginRequest request) {
        return switch (request.loginType()) {
            case ROLE_SELECTION -> "ticket:" + request.loginTicket();
            case EMAIL_CODE -> request.email().trim().toLowerCase(Locale.ROOT);
            case USERNAME_PASSWORD, EMAIL_PASSWORD -> request.account().trim().toLowerCase(Locale.ROOT);
        };
    }

    private LoginPrincipal principal(org.example.music.user.domain.UserEntity user) {
        return new LoginPrincipal(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getUserRole()
        );
    }
}