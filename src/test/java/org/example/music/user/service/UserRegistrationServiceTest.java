package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.example.music.user.dto.UserRegisterRequest;
import org.example.music.user.dto.UserRegisterResponse;
import org.junit.jupiter.api.Test;

class UserRegistrationServiceTest {

    @Test
    void should_delegate_registration_to_transaction_service() {
        UserRegistrationTransactionService transactionService = mock(UserRegistrationTransactionService.class);
        UserRegistrationService service = new UserRegistrationService(transactionService);
        UserRegisterRequest request = new UserRegisterRequest(
                "alice_2026",
                "correct-password",
                null,
                null
        );
        UserRegisterResponse response = mock(UserRegisterResponse.class);
        when(transactionService.register(request)).thenReturn(response);

        assertSame(response, service.register(request));

        verify(transactionService).register(request);
    }
}