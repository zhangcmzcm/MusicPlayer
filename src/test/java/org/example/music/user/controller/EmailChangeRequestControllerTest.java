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
import java.util.function.Supplier;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.config.SecurityConfig;
import org.example.music.security.*;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.service.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmailChangeRequestController.class)
@Import({SecurityConfig.class,ClientIpResolver.class,EmailChangeRequestReader.class})
@ActiveProfiles("test")
class EmailChangeRequestControllerTest {
    static final String PATH="/api/v1/users/me/email-change-requests";
    static final String BODY="{\"newEmail\":\" New@Example.com \"}";
    @Autowired MockMvc mvc;
    @MockitoBean EmailChangeRequestService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,Instant.now());
    UsernamePasswordAuthenticationToken auth(){var a=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));a.setDetails(session);return a;}
    @BeforeEach void setup(){when(service.request(any(),any(),any(),anyString())).thenAnswer(i->{Supplier<EmailChangeRequest> input=i.getArgument(2);assertEquals("new@example.com",input.get().newEmail());return new EmailChangeRequestResponse("A".repeat(43),Instant.parse("2026-11-04T00:05:00Z"));});}
    @Test void should_return_two_fields_no_store_and_take_trusted_identity_and_ip() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(BODY).header("X-Forwarded-For","forged").param("userId","99").with(authentication(auth())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$",aMapWithSize(2))).andExpect(jsonPath("$.challengeId").value("A".repeat(43))).andExpect(jsonPath("$.expiresAt").value("2026-11-04T00:05:00Z")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location"));
        verify(service).request(eq(actor),eq(session),any(),eq("127.0.0.1"));
    }
    @Test void should_require_login_and_typed_context() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var bad=auth();bad.setDetails("forged");mvc.perform(post(PATH).contentType("application/json").content(BODY).with(authentication(bad))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));verifyNoInteractions(service);
    }
    @Test void should_map_parser_errors_and_stable_415() throws Exception {
        mvc.perform(post(PATH).contentType("text/plain").content(BODY).with(authentication(auth()))).andExpect(status().isUnsupportedMediaType()).andExpect(content().contentType("application/problem+json")).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE")).andExpect(jsonPath("$.type").value("urn:problem:music:unsupported_media_type"));
        mvc.perform(post(PATH).contentType("application/json").content("{}").with(authentication(auth()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    @Test void should_map_business_codes_and_keep_reauthentication_before_parsing() throws Exception {
        RuntimeException[] errors={new ReauthenticationRequiredException(),new EmailUnchangedException(),new EmailChangeEmailAlreadyExistsException(),new EmailChangeRequestConflictException(),new EmailChangeRateLimitedException(60),new EmailChangeSendFailedException(new RuntimeException("secret")),new EmailChangeRequestUnavailableException(new RuntimeException("secret")),new CurrentUserRevokedException(),new IllegalStateException("Safe failure",EmailChangeDiagnostics.sanitized(null))};
        String[] codes={"REAUTHENTICATION_REQUIRED","EMAIL_UNCHANGED","EMAIL_ALREADY_EXISTS","EMAIL_CHANGE_REQUEST_CONFLICT","EMAIL_CHANGE_RATE_LIMITED","EMAIL_SEND_FAILED","EMAIL_CHANGE_REQUEST_UNAVAILABLE","TOKEN_REVOKED","INTERNAL_ERROR"};int[] statuses={401,409,409,409,429,502,503,401,500};
        for(int i=0;i<errors.length;i++){doThrow(errors[i]).when(service).request(any(),any(),any(),anyString());var result=mvc.perform(post(PATH).contentType("application/json").content("invalid").with(authentication(auth()))).andExpect(status().is(statuses[i])).andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT))).andExpect(content().string(not(containsString("secret"))));if(statuses[i]==429)result.andExpect(header().string("Retry-After","60"));}
    }
    @Test void should_sanitize_nested_dependency_diagnostics() throws Exception {
        String secret="hidden@example.com code hidden-token";var cause=new RuntimeException(secret,new RuntimeException(secret));cause.addSuppressed(new RuntimeException(secret));
        var logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{doThrow(new EmailChangeRequestUnavailableException(cause)).when(service).request(any(),any(),any(),anyString());mvc.perform(post(PATH).contentType("application/json").content(BODY).with(authentication(auth()))).andExpect(status().isServiceUnavailable());assertFalse(appender.list.isEmpty());for(var event:appender.list){assertFalse(event.getFormattedMessage().contains(secret));assertNull(event.getThrowableProxy().getCause());assertFalse(event.getThrowableProxy().getMessage().contains(secret));}}
        finally{logger.detachAppender(appender);appender.stop();}
    }
}
