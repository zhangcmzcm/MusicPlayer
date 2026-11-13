package org.example.music.favorite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import org.example.music.common.dto.PageResponse;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.AuthenticatedUserPrincipal;
import org.example.music.favorite.dto.FavoriteListItemResponse;
import org.example.music.favorite.exception.FavoriteStoreUnavailableException;
import org.example.music.favorite.service.FavoriteListService;
import org.example.music.favorite.web.FavoriteListErrorAdvice;
import org.example.music.user.domain.UserRole;
import org.example.music.user.service.UserAuthorizationStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(FavoriteListController.class)
@Import({SecurityConfig.class,GlobalExceptionHandler.class,FavoriteListErrorAdvice.class})
class FavoriteListControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private FavoriteListService service;
    @MockitoBean private UserAuthorizationStateService authorization;
    private final AuthenticatedUserPrincipal actor = new AuthenticatedUserPrincipal(7,UserRole.USER);
    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void should_return_explicit_null_fields_and_pagination_with_no_store() throws Exception {
        when(service.list(any(),eq(actor))).thenReturn(new PageResponse<>(List.of(new FavoriteListItemResponse(42L,"title","artist",null,null,"PUBLISHED",null,java.time.Instant.parse("2026-11-12T08:30:00.123456Z"))),0,20,1,1,false));
        mvc.perform(get("/api/v1/users/me/favorites").with(user())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(content().json("""
                    {"items":[{"id":42,"title":"title","artistName":"artist","albumName":null,"durationMs":null,"status":"PUBLISHED","cover":null,"favoritedAt":"2026-11-12T08:30:00.123456Z"}],
                    "page":0,"size":20,"totalElements":1,"totalPages":1,"hasNext":false}
                    """,org.springframework.test.json.JsonCompareMode.STRICT));
        var captor = ArgumentCaptor.forClass(org.example.music.favorite.service.FavoriteListParameters.class);
        verify(service).list(captor.capture(),eq(actor));
        assertEquals(20,captor.getValue().size());
        assertEquals("favoritedAt",captor.getValue().sort().property());
    }

    @Test
    void should_return_empty_array_and_accurate_out_of_range_metadata() throws Exception {
        when(service.list(any(),eq(actor))).thenReturn(new PageResponse<>(List.of(),4,20,3,1,false));
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param("page","4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.page").value(4))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @ParameterizedTest
    @CsvSource(value = {"page|-1", "page|abc", "page|1.5", "page|2147483648", "size|0", "size|101", "size|2147483648", "status|published", "status|ALL", "status|DRAFT,PUBLISHED", "sort|title,ASC", "sort|cover.id,asc", "sort|title", "sort|id,desc,title,asc", "q|song"},delimiter='|')
    void should_reject_invalid_parameters_before_service(String key,String value) throws Exception {
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param(key,value))
                .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings={"page","size","sort","status"})
    void should_reject_empty_and_duplicate_parameters(String key) throws Exception {
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param(key,""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param(key,"1","1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_reject_deep_offset_but_accept_exact_limit() throws Exception {
        when(service.list(any(),eq(actor))).thenReturn(new PageResponse<>(List.of(),100,100,0,0,false));
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param("page","100").param("size","100")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param("page","101").param("size","100"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void should_authenticate_before_parameter_validation() throws Exception {
        mvc.perform(get("/api/v1/users/me/favorites").param("size","0")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void should_return_forbidden_only_after_all_parameters_validate() throws Exception {
        when(service.list(any(),eq(actor))).thenThrow(new AccessDeniedException("forbidden"));
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param("status","DRAFT"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param("status","DRAFT").param("size","0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void should_reject_identity_and_sql_shaped_parameters() throws Exception {
        for (String key : List.of("userId", "q")) {
            mvc.perform(get("/api/v1/users/me/favorites").with(user()).param(key,"7"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/v1/users/me/favorites").with(user()).param("sort","title desc; drop table tracks,asc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void should_return_safe_500_and_503_errors() throws Exception {
        doThrow(new IllegalStateException("internal secret")).when(service).list(any(),eq(actor));
        mvc.perform(get("/api/v1/users/me/favorites").with(user())).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed"))
                .andExpect(header().string("Cache-Control","no-store"));
        doThrow(new FavoriteStoreUnavailableException(new IllegalStateException("private"))).when(service).list(any(),eq(actor));
        mvc.perform(get("/api/v1/users/me/favorites").with(user())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FAVORITE_STORE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control","no-store"));
    }
}
