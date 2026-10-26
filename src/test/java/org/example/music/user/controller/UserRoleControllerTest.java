package org.example.music.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.music.config.SecurityConfig;
import org.example.music.security.UserTokenVersionService;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.UserRoleUpdateResponse;
import org.example.music.user.service.UserRoleAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
    private UserTokenVersionService userTokenVersionService;

    @MockitoBean
    private UserRoleAuthorizationService userRoleAuthorizationService;

    @Test
    void should_update_role_when_actor_is_admin() throws Exception {
        when(userRoleAuthorizationService.updateRole(1, UserRole.ADMIN, 2, UserRole.ADMIN))
                .thenReturn(new UserRoleUpdateResponse(2, "target", UserRole.ADMIN));

        mockMvc.perform(patch("/api/v1/users/2/role")
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "ADMIN"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
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
                        .with(jwt().jwt(token -> token.subject("1").claim("role", "USER"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        verify(userRoleAuthorizationService, org.mockito.Mockito.never()).updateRole(any(), any(), any(), any());
    }
}