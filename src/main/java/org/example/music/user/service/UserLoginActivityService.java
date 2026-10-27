package org.example.music.user.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.example.music.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserLoginActivityService {

    private final UserRepository userRepository;

    public UserLoginActivityService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void recordLogin(Integer userId) {
        userRepository.updateLastLoginAt(userId, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    }
}
