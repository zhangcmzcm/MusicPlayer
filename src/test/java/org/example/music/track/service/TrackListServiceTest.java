package org.example.music.track.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.atLeastOnce;

import java.util.List;
import org.example.music.common.dto.PageResponse;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;

class TrackListServiceTest {
    private final TrackListQueryService query = mock(TrackListQueryService.class);
    private final TrackListService service = new TrackListService(query);

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
}
