package org.example.music.favorite.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;

class FavoriteListServiceTest {
    private final FavoriteListQueryService query = mock(FavoriteListQueryService.class);
    private final FavoriteListService service = new FavoriteListService(query);

    @ParameterizedTest
    @EnumSource(TrackStatus.class)
    void should_restrict_non_admin_to_published(TrackStatus status) {
        var p = new FavoriteListParameters(0,20,new FavoriteListSort("favoritedAt", false),status);
        var user = new AuthenticatedUserPrincipal(7, UserRole.USER);
        if (status != TrackStatus.PUBLISHED) {
            assertThrows(AccessDeniedException.class, () -> service.list(p, user));
            verifyNoInteractions(query);
        } else {
            service.list(p, user);
            verify(query).query(7,p,TrackStatus.PUBLISHED);
        }
        service.list(p, new AuthenticatedUserPrincipal(8, UserRole.ADMIN));
        verify(query).query(8,p,status);
    }

    @Test
    void should_apply_role_defaults_and_return_response_unchanged() {
        var p = new FavoriteListParameters(0,20,new FavoriteListSort("favoritedAt", false),null);
        var expected = new org.example.music.common.dto.PageResponse<org.example.music.favorite.dto.FavoriteListItemResponse>(java.util.List.of(),0,20,0,0,false);
        when(query.query(8,p,null)).thenReturn(expected);
        assertSame(expected,service.list(p,new AuthenticatedUserPrincipal(8,UserRole.ADMIN)));
        service.list(p,new AuthenticatedUserPrincipal(8,UserRole.USER));
        verify(query).query(8,p,TrackStatus.PUBLISHED);
    }

    @Test
    void should_preserve_failure_causes_and_not_misclassify_inconsistency() {
        var p = new FavoriteListParameters(0,20,new FavoriteListSort("id",false),null);
        var actor = new AuthenticatedUserPrincipal(7,UserRole.ADMIN);
        for (RuntimeException failure : java.util.List.of(new DataAccessResourceFailureException("private"), new TransactionSystemException("private"))) {
            when(query.query(7,p,null)).thenThrow(failure);
            assertSame(failure,assertThrows(FavoriteStoreUnavailableException.class, () -> service.list(p,actor)).getCause());
            reset(query);
        }
        var failure = new IllegalStateException("inconsistent");
        when(query.query(7,p,null)).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class, () -> service.list(p,actor)));
    }
}
