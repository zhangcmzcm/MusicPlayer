package org.example.music.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UserRoleUpdateResponse;
import org.example.music.user.exception.LastAdminCannotBeDemotedException;
import org.example.music.user.exception.SelfRoleModificationException;
import org.example.music.user.exception.UserNotFoundException;
import org.example.music.user.service.UserRoleAuthorizationService;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserRoleController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserRoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserAuthorizationStateService userAuthorizationStateService;


    @MockitoBean
    private UserRoleAuthorizationService userRoleAuthorizationService;

    @Test
    void should_update_role_when_actor_is_admin() throws Exception {
        when(userRoleAuthorizationService.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN))
                .thenReturn(new UserRoleUpdateResponse(2, "target", UserRole.ADMIN));

        mockMvc.perform(patch("/api/v1/users/2/role")
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        verify(userRoleAuthorizationService).updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN);
    }

    @Test
    void should_return_403_when_actor_is_not_admin() throws Exception {
        mockMvc.perform(patch("/api/v1/users/2/role")
                        .with(authentication(userAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        verify(userRoleAuthorizationService, never()).updateRole(any(), any(), any(), any());
    }

    @Test
    void should_return_400_when_path_id_is_not_positive() throws Exception {
        mockMvc.perform(patch("/api/v1/users/0/role")
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest());

        verify(userRoleAuthorizationService, never()).updateRole(any(), any(), any(), any());
    }

    @Test
    void should_return_403_when_actor_modifies_own_role() throws Exception {
        when(userRoleAuthorizationService.updateRole(1, UserRole.ADMIN, 1, UserRole.USER))
                .thenThrow(new SelfRoleModificationException());

        mockMvc.perform(patch("/api/v1/users/1/role")
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CANNOT_MODIFY_SELF_ROLE"));
    }

    @Test
    void should_return_404_when_target_user_does_not_exist() throws Exception {
        when(userRoleAuthorizationService.updateRole(1, UserRole.ADMIN, 99, UserRole.USER))
                .thenThrow(new UserNotFoundException());

        mockMvc.perform(patch("/api/v1/users/99/role")
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void should_return_409_when_last_admin_would_be_demoted() throws Exception {
        when(userRoleAuthorizationService.updateRole(1, UserRole.ADMIN, 2, UserRole.USER))
                .thenThrow(new LastAdminCannotBeDemotedException());

        mockMvc.perform(patch("/api/v1/users/2/role")
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_CANNOT_BE_DEMOTED"));
    }

    @Test
    void should_return_400_when_role_value_is_invalid() throws Exception {
        mockMvc.perform(patch("/api/v1/users/2/role")
                        .with(authentication(adminAuthentication()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPER_ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUserPrincipal(1, UserRole.ADMIN),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private UsernamePasswordAuthenticationToken userAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUserPrincipal(1, UserRole.USER),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
}
