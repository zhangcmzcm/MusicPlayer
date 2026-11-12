package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.favorite.repository.FavoriteListRepository;
import org.example.music.favorite.repository.FavoriteListRow;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.MediaAssetKind;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.repository.MediaAssetRepository;
import org.example.music.track.repository.TrackListCoverRow;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class FavoriteListProxyTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @EnableTransactionManagement
    static class Config {
        @Bean FavoriteListRepository repository() { return mock(FavoriteListRepository.class); }
        @Bean MediaAssetRepository assets() { return mock(MediaAssetRepository.class); }
        @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
        @Bean FavoriteListQueryService query(FavoriteListRepository repository, MediaAssetRepository assets) {
            return new FavoriteListQueryService(repository,assets);
        }
        @Bean FavoriteListService service(FavoriteListQueryService query) { return new FavoriteListService(query); }
    }
    private AnnotationConfigApplicationContext context;
    private FavoriteListRepository repository;
    private PlatformTransactionManager manager;
    private TransactionStatus transaction;
    private FavoriteListService service;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7,UserRole.USER);
    private final FavoriteListParameters params = new FavoriteListParameters(0,20,new FavoriteListSort("favoritedAt",false),null);

    @BeforeEach
    void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        repository = context.getBean(FavoriteListRepository.class);
        manager = context.getBean(PlatformTransactionManager.class);
        service = context.getBean(FavoriteListService.class);
        transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); context.close(); }

    @Test
    void should_use_repeatable_read_and_commit_before_returning() {
        service.list(params,actor);
        var definition = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        var order = inOrder(manager,repository);
        order.verify(manager).getTransaction(definition.capture());
        assertTrue(definition.getValue().isReadOnly());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getValue().getIsolationLevel());
        order.verify(repository).findRows(7,TrackStatus.PUBLISHED,0,20,params.sort());
        order.verify(repository).count(7,TrackStatus.PUBLISHED);
        order.verify(manager).commit(transaction);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "no_cover", "cover"})
    void should_translate_commit_failure_for_every_page_shape(String shape) {
        if (!shape.equals("empty")) {
            when(repository.findRows(7,TrackStatus.PUBLISHED,0,20,params.sort())).thenReturn(List.of(
                    new FavoriteListRow(42L,"title","artist",null,null,TrackStatus.PUBLISHED,
                            shape.equals("cover") ? 3L : null,Instant.EPOCH)));
            when(repository.count(7,TrackStatus.PUBLISHED)).thenReturn(1L);
        }
        if (shape.equals("cover")) {
            when(context.getBean(MediaAssetRepository.class).findListCovers(Set.of(3L))).thenReturn(List.of(
                    new TrackListCoverRow(3L,MediaAssetKind.COVER,"image/png",1L,"a".repeat(64),"test","key")));
        }
        var failure = new TransactionSystemException("commit unavailable");
        doThrow(failure).when(manager).commit(transaction);
        assertSame(failure,assertThrows(FavoriteStoreUnavailableException.class,() -> service.list(params,actor)).getCause());
    }
    @Test
    void should_translate_begin_failure_without_querying() {
        var failure = new CannotCreateTransactionException("begin unavailable");
        when(manager.getTransaction(any())).thenThrow(failure);
        assertSame(failure,assertThrows(FavoriteStoreUnavailableException.class,() -> service.list(params,actor)).getCause());
        verifyNoInteractions(repository);
    }
    @Test
    void should_require_authentication_before_transaction() {
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationException.class,() -> service.list(params,actor));
        verifyNoInteractions(manager,repository);
    }
}
