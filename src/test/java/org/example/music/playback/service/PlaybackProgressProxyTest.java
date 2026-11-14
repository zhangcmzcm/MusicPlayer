package org.example.music.playback.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.music.playback.dto.SavePlaybackProgressRequest;
import org.example.music.playback.exception.PlaybackProgressStoreUnavailableException;
import org.example.music.playback.repository.PlaybackProgressRepository;
import org.example.music.playback.repository.PlaybackProgressRow;
import org.example.music.playback.repository.PlaybackProgressTrackRow;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class PlaybackProgressProxyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @EnableTransactionManagement
    static class Config {
        @Bean PlaybackProgressRepository repository() { return mock(PlaybackProgressRepository.class); }
        @Bean PlaybackProgressRateLimiter limiter() { return mock(PlaybackProgressRateLimiter.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean PlaybackProgressTransactionService transaction(PlaybackProgressRepository repository) {
            return new PlaybackProgressTransactionService(repository, Clock.systemUTC());
        }
        @Bean PlaybackProgressService service(PlaybackProgressRateLimiter limiter, PlaybackProgressTransactionService transaction) {
            return new PlaybackProgressService(limiter, transaction);
        }
    }

    private AnnotationConfigApplicationContext context;
    private PlaybackProgressRepository repository;
    private PlaybackProgressRateLimiter limiter;
    private PlatformTransactionManager manager;
    private TransactionStatus transaction;
    private PlaybackProgressService service;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);
    private final SavePlaybackProgressRequest input = new SavePlaybackProgressRequest(123L, 83L, 42L);

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        repository = context.getBean(PlaybackProgressRepository.class);
        limiter = context.getBean(PlaybackProgressRateLimiter.class);
        manager = context.getBean(PlatformTransactionManager.class);
        service = context.getBean(PlaybackProgressService.class);
        transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(repository.lockTrackForShare(123L)).thenReturn(Optional.of(new PlaybackProgressTrackRow(TrackStatus.PUBLISHED, null)));
        when(repository.lockProgress(7, "device")).thenReturn(new PlaybackProgressRow("device", 123L, 83, 42, Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void should_commit_transaction_before_service_returns() {
        service.save("device", input, actor);
        var order = inOrder(limiter, manager, repository);
        order.verify(limiter).consumeOrThrow(7);
        order.verify(manager).getTransaction(any());
        order.verify(repository).lockTrackForShare(123L);
        order.verify(repository).insertIfAbsent(eq(7), eq("device"), eq(123L), eq(83L), eq(42L), any());
        order.verify(repository).lockProgress(7, "device");
        order.verify(manager).commit(transaction);
    }

    @Test
    void should_translate_transaction_begin_failure() {
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("begin private"));
        assertThrows(PlaybackProgressStoreUnavailableException.class, () -> service.save("device", input, actor));
        verifyNoInteractions(repository);
    }

    @Test
    void should_translate_transaction_commit_failure() {
        var failure = new TransactionSystemException("commit private");
        doThrow(failure).when(manager).commit(transaction);
        assertSame(failure, assertThrows(PlaybackProgressStoreUnavailableException.class,
                () -> service.save("device", input, actor)).getCause());
    }

    @Test
    void should_require_authentication_before_quota_and_transaction() {
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationException.class, () -> service.save("device", input, actor));
        verifyNoInteractions(limiter, manager, repository);
    }
}
