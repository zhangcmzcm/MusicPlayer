package org.example.music.user.service;

import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.springframework.stereotype.Service;

@Service
public class UserRegistrationService {

    private final UserRegistrationTransactionService userRegistrationTransactionService;

    public UserRegistrationService(UserRegistrationTransactionService userRegistrationTransactionService) {
        this.userRegistrationTransactionService = userRegistrationTransactionService;
    }

    public UserRegisterResponse register(UserRegisterRequest request) {
        return userRegistrationTransactionService.register(request);
    }
}