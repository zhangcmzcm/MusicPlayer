package org.example.music.user.service;

import java.util.Optional;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.exception.AuthStateStoreUnavailableException;

import org.example.music.user.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class UserAuthorizationStateService {

    private final UserRepository userRepository;

    public UserAuthorizationStateService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<UserAuthorizationSnapshot> findSnapshot(Integer userId) {
        try {
            return userRepository.findAuthorizationSnapshot(userId);
        } catch (DataAccessException exception) {
            throw new AuthStateStoreUnavailableException("Authorization state store is unavailable", exception);
        }
    }
}
