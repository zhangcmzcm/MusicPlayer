package org.example.music.track.service;

import org.example.music.common.dto.PageResponse;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.track.domain.TrackStatus;
import org.example.music.track.dto.TrackListItemResponse;
import org.example.music.track.exception.TrackStoreUnavailableException;
import org.example.music.user.domain.UserRole;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class TrackListService {
    private final TrackListQueryService query;

    public TrackListService(TrackListQueryService query) {
        this.query = query;
    }

    @PreAuthorize("isAuthenticated()")
    public PageResponse<TrackListItemResponse> list(TrackListParameters parameters, AuthenticatedUserPrincipal actor) {
        TrackStatus status = parameters.requestedStatus();
        if (actor.loginRole() != UserRole.ADMIN) {
            if (status != null && status != TrackStatus.PUBLISHED) {
                throw new AccessDeniedException("Track status filter is forbidden");
            }
            status = TrackStatus.PUBLISHED;
        }
        try {
            return query.query(parameters, status);
        } catch (DataAccessException | TransactionException exception) {
            throw new TrackStoreUnavailableException("Track list could not be read", exception);
        }
    }
}
