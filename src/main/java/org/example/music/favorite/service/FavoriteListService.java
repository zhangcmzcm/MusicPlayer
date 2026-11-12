package org.example.music.favorite.service;

import org.example.music.common.dto.PageResponse;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.favorite.dto.FavoriteListItemResponse;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.user.domain.UserRole;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class FavoriteListService {
    private final FavoriteListQueryService query;

    public FavoriteListService(FavoriteListQueryService query) {
        this.query = query;
    }

    @PreAuthorize("isAuthenticated()")
    public PageResponse<FavoriteListItemResponse> list(FavoriteListParameters parameters, AuthenticatedUserPrincipal actor) {
        TrackStatus status = parameters.requestedStatus();
        if (actor.loginRole() != UserRole.ADMIN) {
            if (status != null && status != TrackStatus.PUBLISHED) {
                throw new AccessDeniedException("Track status filter is forbidden");
            }
            status = TrackStatus.PUBLISHED;
        }
        try {
            return query.query(actor.userId(), parameters, status);
        } catch (DataAccessException | TransactionException exception) {
            throw new FavoriteStoreUnavailableException(exception);
        }
    }
}
