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
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.config.SecurityConfig;
import org.example.music.security.*;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.ReauthenticationEmailRequestResponse;
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

@WebMvcTest(ReauthenticationEmailRequestController.class)
@Import({SecurityConfig.class,ClientIpResolver.class})
@ActiveProfiles("test")
class ReauthenticationEmailRequestControllerTest {
    static final String PATH="/api/v1/users/me/reauthentication-email-requests";
    @Autowired MockMvc mvc;
    @MockitoBean ReauthenticationEmailRequestService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,null);
    UsernamePasswordAuthenticationToken auth() {
        var result=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));result.setDetails(session);return result;
    }
    @Test void should_return_two_fields_no_store_and_ignore_forged_target_and_ip_headers() throws Exception {
        when(service.request(eq(actor),eq(session),anyString())).thenReturn(new ReauthenticationEmailRequestResponse("A".repeat(43),Instant.parse("2026-11-03T12:00:00Z")));
        mvc.perform(post(PATH).param("userId","99").param("email","victim@example.com").header("X-Forwarded-For","forged").header("X-User-Id","99").with(authentication(auth())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$",aMapWithSize(2)))
                .andExpect(jsonPath("$.challengeId").value("A".repeat(43))).andExpect(jsonPath("$.expiresAt").value("2026-11-03T12:00:00Z"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location"));
        verify(service).request(actor,session,"127.0.0.1");
    }
    @ParameterizedTest @ValueSource(strings={" ","{}","null","[]","{\"userId\":99}","payload"})
    void should_reject_every_nonempty_body_without_content_type_requirement(String body) throws Exception {
        mvc.perform(post(PATH).content(body).with(authentication(auth()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));verifyNoInteractions(service);
    }
    @Test void should_check_stream_even_with_unknown_length_and_chunked_header() throws Exception {
        mvc.perform(post(PATH).content("{}").header("Transfer-Encoding","chunked").with(request->new org.springframework.mock.web.MockHttpServletRequest(request.getServletContext()) {
            {setMethod("POST");setRequestURI(PATH);setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            @Override public int getContentLength(){return -1;}
            @Override public long getContentLengthLong(){return -1;}
        }).with(authentication(auth()))).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void should_require_authenticated_typed_details() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var bad=auth();bad.setDetails("forged");mvc.perform(post(PATH).with(authentication(bad))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));verifyNoInteractions(service);
    }
    @Test void should_read_only_first_byte_and_translate_stream_failure() throws Exception {
        var controller=new ReauthenticationEmailRequestController(service,new ClientIpResolver());
        var request=mock(jakarta.servlet.http.HttpServletRequest.class);
        var stream=mock(jakarta.servlet.ServletInputStream.class);when(request.getInputStream()).thenReturn(stream);when(stream.read()).thenReturn((int)'x');
        assertThrows(ReauthenticationEmailInvalidRequestException.class,()->controller.request(request,auth()));verify(stream,times(1)).read();verifyNoInteractions(service);
        when(stream.read()).thenThrow(new java.io.IOException("sensitive"));
        assertThrows(ReauthenticationEmailInvalidRequestException.class,()->controller.request(request,auth()));verifyNoInteractions(service);
    }
    @Test void should_map_stable_business_errors() throws Exception {
        RuntimeException[] errors={new CurrentEmailNotBoundException(),new ReauthenticationEmailRequestConflictException(),new ReauthenticationEmailRateLimitedException(60),new ReauthenticationEmailSendFailedException(null),new ReauthenticationEmailRequestUnavailableException(null),new CurrentUserRevokedException()};
        String[] codes={"CURRENT_EMAIL_NOT_BOUND","REAUTH_EMAIL_REQUEST_CONFLICT","REAUTH_EMAIL_RATE_LIMITED","EMAIL_SEND_FAILED","REAUTH_EMAIL_REQUEST_UNAVAILABLE","TOKEN_REVOKED"};int[] statuses={409,409,429,502,503,401};
        for(int i=0;i<errors.length;i++) {
            doThrow(errors[i]).when(service).request(any(),any(),anyString());
            var response=mvc.perform(post(PATH).with(authentication(auth()))).andExpect(status().is(statuses[i]))
                    .andExpect(content().contentType("application/problem+json")).andExpect(jsonPath("$.code").value(codes[i]))
                    .andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT)))
                    .andExpect(header().string("Cache-Control",containsString("no-store")));
            if(statuses[i]==429)response.andExpect(header().string("Retry-After","60"));
        }
    }
    @Test void should_not_log_original_cause_or_suppressed_values() throws Exception {
        String secret="alice@example.com 012345 hidden-hash hidden-challenge";
        var cause=new RuntimeException(secret,new RuntimeException(secret));cause.addSuppressed(new RuntimeException(secret));
        Logger logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            when(service.request(any(),any(),anyString())).thenThrow(new ReauthenticationEmailRequestUnavailableException(cause));
            mvc.perform(post(PATH).with(authentication(auth()))).andExpect(status().isServiceUnavailable()).andExpect(content().string(not(containsString("alice@example.com"))));
            assertFalse(appender.list.isEmpty());for(var event:appender.list){assertFalse(event.getFormattedMessage().contains(secret));var diagnostic=event.getThrowableProxy();assertNotNull(diagnostic);assertFalse(diagnostic.getMessage().contains(secret));assertNull(diagnostic.getCause());assertTrue(diagnostic.getSuppressed()==null||diagnostic.getSuppressed().length==0);}
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
