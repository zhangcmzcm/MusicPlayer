package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.favorite.repository.FavoriteRepository;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class UnfavoriteTrackProxyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @EnableTransactionManagement
    static class Config {
        @Bean FavoriteRepository favorites() { return mock(FavoriteRepository.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean UnfavoriteTrackTransactionService transaction(FavoriteRepository repository) {
            return new UnfavoriteTrackTransactionService(repository);
        }
        @Bean UnfavoriteTrackService service(UnfavoriteTrackTransactionService transaction) {
            return new UnfavoriteTrackService(transaction);
        }
    }

    private AnnotationConfigApplicationContext context;
    private FavoriteRepository favorites;
    private PlatformTransactionManager manager;
    private TransactionStatus transaction;
    private UnfavoriteTrackService service;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7, UserRole.USER);

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        favorites = context.getBean(FavoriteRepository.class);
        manager = context.getBean(PlatformTransactionManager.class);
        service = context.getBean(UnfavoriteTrackService.class);
        transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(favorites.lockTrackStatus(42L)).thenReturn(Optional.of(TrackStatus.PUBLISHED));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void should_commit_after_lock_and_delete_before_service_returns() {
        service.unfavorite(42L, actor);
        var order = inOrder(manager, favorites);
        order.verify(manager).getTransaction(any());
        order.verify(favorites).lockTrackStatus(42L);
        order.verify(favorites).deleteByUserAndTrack(7, 42L);
        order.verify(manager).commit(transaction);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void should_translate_commit_failure_even_when_track_or_favorite_is_missing(int changed) {
        if (changed == -1) {
            when(favorites.lockTrackStatus(42L)).thenReturn(Optional.empty());
        } else {
            when(favorites.deleteByUserAndTrack(7, 42L)).thenReturn(changed);
        }
        var failure = new TransactionSystemException("commit unavailable");
        doThrow(failure).when(manager).commit(transaction);
        assertSame(failure, assertThrows(FavoriteStoreUnavailableException.class,
                () -> service.unfavorite(42L, actor)).getCause());
        verify(favorites, times(changed == -1 ? 0 : 1)).deleteByUserAndTrack(7, 42L);
    }

    @Test
    void should_translate_begin_failure_before_querying_repository() {
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("begin unavailable"));
        assertThrows(FavoriteStoreUnavailableException.class, () -> service.unfavorite(42L, actor));
        verifyNoInteractions(favorites);
    }

    @Test
    void should_require_authentication_before_starting_transaction() {
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationException.class, () -> service.unfavorite(42L, actor));
        verifyNoInteractions(manager, favorites);
    }
}
