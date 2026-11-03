package org.example.music.user.controller;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.*;
import org.example.music.user.domain.*;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReauthenticationController.class)
@Import({SecurityConfig.class,ReauthenticationRequestReader.class}) @ActiveProfiles("test")
class ReauthenticationControllerTest {
    static final String PATH="/api/v1/users/me/reauthentications";
    static final String BODY="{\"method\":\"PASSWORD\",\"password\":\"test-password\"}";
    @Autowired MockMvc mvc;
    @MockitoBean ReauthenticationService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext context=new AuthenticatedSessionContext(7,null);
    UsernamePasswordAuthenticationToken auth(){var value=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));value.setDetails(context);return value;}

    @Test void should_return_exact_four_fields_no_store_and_ignore_forged_identity_headers() throws Exception {
        when(service.reauthenticate(eq(actor),eq(context),any())).thenReturn(new ReauthenticationResponse("new-token","Bearer",Instant.parse("2026-11-04T12:00:00Z"),Instant.parse("2026-11-03T12:00:00Z")));
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("X-User-Id","999").header("X-Auth-Time","9999999999").param("userId","999").param("method","EMAIL_CODE").with(authentication(auth())))
                .andExpect(status().isOk()).andExpect(content().contentType("application/json")).andExpect(jsonPath("$",aMapWithSize(4)))
                .andExpect(jsonPath("$.accessToken").value("new-token")).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.authenticatedAt").value("2026-11-03T12:00:00Z")).andExpect(jsonPath("$.expiresAt").value("2026-11-04T12:00:00Z"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location"));
        verify(service).reauthenticate(actor,context,new ReauthenticationRequest(ReauthenticationMethod.PASSWORD,"test-password",null,null));
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "null", "[]", "{\"method\":\"PASSWORD\",\"password\":\"a\",\"userId\":99}", "{\"method\":\"PASSWORD\",\"password\":\"a\",\"password\":\"b\"}"})
    void should_reject_bad_bodies_before_service(String body) throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body).with(authentication(auth()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(header().string("Cache-Control",containsString("no-store")));verifyNoInteractions(service);
    }
    @Test void should_map_missing_or_wrong_media_type_locally_to_400() throws Exception {
        mvc.perform(post(PATH).content(BODY).with(authentication(auth()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(PATH).contentType("text/plain").content(BODY).with(authentication(auth()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));verifyNoInteractions(service);
    }
    @Test void should_require_authentication_and_typed_context() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var bad=auth();bad.setDetails("forged");mvc.perform(post(PATH).contentType("application/json").content(BODY).with(authentication(bad))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));verifyNoInteractions(service);
    }
    @Test void should_map_all_business_codes_without_redirect_or_internal_data() throws Exception {
        RuntimeException[] errors={new ReauthenticationFailedException(),new ReauthenticationRateLimitedException(900),new ReauthenticationUnavailableException(new RuntimeException("secret")),new CurrentEmailNotBoundException(),new CurrentUserRevokedException(),new IllegalStateException("Safe unexpected failure",ReauthenticationDiagnostics.sanitized(new RuntimeException("secret")))};
        String[] codes={"REAUTHENTICATION_FAILED","REAUTHENTICATION_RATE_LIMITED","REAUTHENTICATION_UNAVAILABLE","CURRENT_EMAIL_NOT_BOUND","TOKEN_REVOKED","INTERNAL_ERROR"};int[] statuses={401,429,503,409,401,500};
        for(int i=0;i<errors.length;i++){
            doThrow(errors[i]).when(service).reauthenticate(any(),any(),any());
            var result=mvc.perform(post(PATH).contentType("application/json").content(BODY).with(authentication(auth())))
                    .andExpect(status().is(statuses[i])).andExpect(content().contentType("application/problem+json")).andExpect(jsonPath("$.code").value(codes[i]))
                    .andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT)))
                    .andExpect(jsonPath("$.remainingAttempts").doesNotExist()).andExpect(header().doesNotExist("Location"))
                    .andExpect(header().string("Cache-Control",containsString("no-store"))).andExpect(content().string(not(containsString("secret"))));
            if(statuses[i]==429)result.andExpect(header().string("Retry-After","900"));
        }
    }
    @Test void should_log_only_sanitized_dependency_diagnostics() throws Exception {
        String secret="private@example.com 012345 hidden-hash hidden-id hidden-token";var cause=new RuntimeException(secret,new RuntimeException(secret));cause.addSuppressed(new RuntimeException(secret));
        var logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{doThrow(new ReauthenticationUnavailableException(cause)).when(service).reauthenticate(any(),any(),any());mvc.perform(post(PATH).contentType("application/json").content(BODY).with(authentication(auth()))).andExpect(status().isServiceUnavailable()).andExpect(content().string(not(containsString("private@example.com"))));assertFalse(appender.list.isEmpty());for(var event:appender.list){assertFalse(event.getFormattedMessage().contains(secret));var diagnostic=event.getThrowableProxy();assertNotNull(diagnostic);assertFalse(diagnostic.getMessage().contains(secret));assertNull(diagnostic.getCause());assertTrue(diagnostic.getSuppressed()==null||diagnostic.getSuppressed().length==0);}}finally{logger.detachAppender(appender);appender.stop();}
    }
}
