package org.example.music.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.example.music.common.security.SecurityProblemResponseWriter;
import org.example.music.user.exception.AuthStateStoreUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenVersionAuthenticationFilter extends OncePerRequestFilter {

    private final UserTokenVersionService userTokenVersionService;
    private final SecurityProblemResponseWriter securityProblemResponseWriter;

    public TokenVersionAuthenticationFilter(
            UserTokenVersionService userTokenVersionService,
            SecurityProblemResponseWriter securityProblemResponseWriter
    ) {
        this.userTokenVersionService = userTokenVersionService;
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
            Integer userId = Integer.valueOf(jwtAuthenticationToken.getToken().getSubject());
            Object claim = jwtAuthenticationToken.getToken().getClaims().get("tokenVersion");
            if (!(claim instanceof Number tokenVersion)
                    || userTokenVersionService.current(userId) != tokenVersion.longValue()) {
                write(response, request, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token revoked");
                return;
            }
        } catch (NumberFormatException exception) {
            write(response, request, HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token revoked");
            return;
        } catch (AuthStateStoreUnavailableException exception) {
            write(response, request, HttpStatus.SERVICE_UNAVAILABLE, "AUTH_STATE_STORE_UNAVAILABLE", "Authentication state store unavailable");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void write(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String detail
    ) throws IOException {
        securityProblemResponseWriter.write(
                request,
                response,
                status,
                code,
                status == HttpStatus.UNAUTHORIZED ? "Token revoked" : "Authentication state store unavailable",
                detail
        );
    }
}