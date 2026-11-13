package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;

import java.util.List;
import org.example.music.common.dto.PageResponse;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.track.exception.TrackSearchRateLimitUnavailableException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;

class TrackListServiceTest {
    private final TrackListQueryService query = mock(TrackListQueryService.class);
    private final TrackSearchRateLimiter searchLimiter = mock(TrackSearchRateLimiter.class);
    private final TrackListService service = new TrackListService(query, searchLimiter);

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_apply_role_matrix_before_querying(TrackStatus state) {
        var params = new TrackListParameters(0,20,new TrackListSort("id",false),state);
        var user = new AuthenticatedUserPrincipal(7,UserRole.USER);
        if (state != TrackStatus.PUBLISHED) {
            assertThrows(AccessDeniedException.class, () -> service.list(params,user));
            verifyNoInteractions(query);
        } else {
            service.list(params,user);
            verify(query).query(params,TrackStatus.PUBLISHED);
        }
        service.list(params,new AuthenticatedUserPrincipal(8,UserRole.ADMIN));
        verify(query,atLeastOnce()).query(params,state);
    }

    @Test
    void should_use_role_defaults_and_preserve_response() {
        var params = new TrackListParameters(0,20,new TrackListSort("id",false),null);
        var expected = new PageResponse<org.example.music.track.dto.TrackListItemResponse>(List.of(),0,20,0,0,false);
        when(query.query(params,null)).thenReturn(expected);
        assertSame(expected,service.list(params,new AuthenticatedUserPrincipal(8,UserRole.ADMIN)));
        service.list(params,new AuthenticatedUserPrincipal(8,UserRole.USER));
        verify(query).query(params,TrackStatus.PUBLISHED);
    }

    @Test
    void should_translate_database_and_transaction_creation_failures() {
        var params = new TrackListParameters(0,20,new TrackListSort("id",false),null);
        var admin = new AuthenticatedUserPrincipal(8,UserRole.ADMIN);
        doThrow(new DataAccessResourceFailureException("database")).when(query).query(params,null);
        assertThrows(TrackStoreUnavailableException.class, () -> service.list(params,admin));
        doThrow(new CannotCreateTransactionException("database")).when(query).query(params,null);
        assertThrows(TrackStoreUnavailableException.class, () -> service.list(params,admin));
    }

    @Test
    void should_consume_search_quota_before_query_even_when_database_fails() {
        var parameters = new TrackListParameters(0, 20, new TrackListSort("id", false), null, List.of("晴"));
        var actor = new AuthenticatedUserPrincipal(7, UserRole.USER);
        doThrow(new DataAccessResourceFailureException("database")).when(query).query(parameters, TrackStatus.PUBLISHED);
        assertThrows(TrackStoreUnavailableException.class, () -> service.list(parameters, actor));
        var order = inOrder(searchLimiter, query);
        order.verify(searchLimiter).consumeOrThrow(7);
        order.verify(query).query(parameters, TrackStatus.PUBLISHED);
    }

    @Test
    void should_require_state_permission_before_search_quota() {
        var parameters = new TrackListParameters(0, 20, new TrackListSort("id", false), TrackStatus.DRAFT, List.of("晴"));
        assertThrows(AccessDeniedException.class,
                () -> service.list(parameters, new AuthenticatedUserPrincipal(7, UserRole.USER)));
        verifyNoInteractions(searchLimiter, query);
    }

    @Test
    void should_skip_search_quota_for_plain_list_and_keep_dependency_failure_distinct() {
        var actor = new AuthenticatedUserPrincipal(7, UserRole.USER);
        service.list(new TrackListParameters(0, 20, new TrackListSort("id", false), null), actor);
        verifyNoInteractions(searchLimiter);
        var search = new TrackListParameters(0, 20, new TrackListSort("id", false), null, List.of("晴"));
        var failure = new TrackSearchRateLimitUnavailableException(null);
        doThrow(failure).when(searchLimiter).consumeOrThrow(7);
        assertSame(failure, assertThrows(TrackSearchRateLimitUnavailableException.class, () -> service.list(search, actor)));
    }
}
