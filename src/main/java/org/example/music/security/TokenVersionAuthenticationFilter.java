package org.example.music.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.example.music.common.security.SecurityProblemResponseWriter;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserRole;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.example.music.user.exception.InvalidTokenException;
import org.example.music.user.service.UserAuthorizationStateService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenVersionAuthenticationFilter extends OncePerRequestFilter {

    private final UserAuthorizationStateService userAuthorizationStateService;
    private final SecurityProblemResponseWriter securityProblemResponseWriter;

    public TokenVersionAuthenticationFilter(
            UserAuthorizationStateService userAuthorizationStateService,
            SecurityProblemResponseWriter securityProblemResponseWriter
    ) {
        this.userAuthorizationStateService = userAuthorizationStateService;
        this.securityProblemResponseWriter = securityProblemResponseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Integer userId = parseUserId(jwtAuthenticationToken.getToken().getSubject());
            long tokenVersion = parseTokenVersion(jwtAuthenticationToken.getToken().getClaims().get("tokenVersion"));
            UserRole loginRole = parseLoginRole(jwtAuthenticationToken.getToken().getClaims().get("role"));

            Optional<UserAuthorizationSnapshot> snapshotResult =
                    userAuthorizationStateService.findSnapshot(userId);
            if (snapshotResult.isEmpty()) {
                write(response, request, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token revoked", "Token revoked");
                return;
            }
            UserAuthorizationSnapshot snapshot = snapshotResult.get();
            if (snapshot.tokenVersion() != tokenVersion || !loginRole.isAllowedBy(snapshot.userRole())) {
                write(response, request, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token revoked", "Token revoked");
                return;
            }

            AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(userId, loginRole);
            UsernamePasswordAuthenticationToken authorized = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + loginRole.name()))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authorized);
            SecurityContextHolder.setContext(context);
        } catch (InvalidTokenException | IllegalArgumentException exception) {
            write(response, request, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid token", "Token required claims are missing or invalid");
            return;
        } catch (AuthStateStoreUnavailableException exception) {
            write(response, request, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_STATE_STORE_UNAVAILABLE", "Authentication state store unavailable", "Authentication state store unavailable");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Integer parseUserId(String subject) {
        if (subject == null || !subject.matches("^[1-9]\\d*$")) {
            throw new InvalidTokenException();
        }
        try {
            return Integer.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw new InvalidTokenException();
        }
    }

    private long parseTokenVersion(Object claim) {
        if (claim == null || !String.valueOf(claim).matches("^\\d+$")) {
            throw new InvalidTokenException();
        }
        try {
            return Long.parseLong(String.valueOf(claim));
        } catch (NumberFormatException exception) {
            throw new InvalidTokenException();
        }
    }

    private UserRole parseLoginRole(Object claim) {
        if (!(claim instanceof String role) || role.isBlank()) {
            throw new InvalidTokenException();
        }
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw new InvalidTokenException();
        }
    }


    private void write(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title,
            String detail
    ) throws IOException {
        securityProblemResponseWriter.write(request, response, status, code, title, detail);
    }

}
