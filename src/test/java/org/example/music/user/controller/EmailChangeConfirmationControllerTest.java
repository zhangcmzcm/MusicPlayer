package org.example.music.user.controller;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.example.music.common.exception.GlobalExceptionHandler;
import org.example.music.config.SecurityConfig;
import org.example.music.security.*;
import org.example.music.user.domain.UserRole;
import org.example.music.user.dto.EmailChangeConfirmationRequest;
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

@WebMvcTest(EmailChangeConfirmationController.class)
@Import({SecurityConfig.class,EmailChangeConfirmationRequestReader.class}) @ActiveProfiles("test")
class EmailChangeConfirmationControllerTest {
    static final String PATH="/api/v1/users/me/email",BODY="{\"challengeId\":\""+"A".repeat(43)+"\",\"code\":\"012345\"}";
    @Autowired MockMvc mvc;
    @MockitoBean EmailChangeConfirmationService service;
    @MockitoBean UserAuthorizationStateService authorizationState;
    final AuthenticatedUserPrincipal actor=new AuthenticatedUserPrincipal(42,UserRole.USER);
    final AuthenticatedSessionContext session=new AuthenticatedSessionContext(7,Instant.now());
    UsernamePasswordAuthenticationToken auth(){var a=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));a.setDetails(session);return a;}
    @BeforeEach void setup(){doAnswer(i->{Supplier<EmailChangeConfirmationRequest> input=i.getArgument(2);assertEquals("012345",input.get().code());return null;}).when(service).confirm(any(),any(),any());}
    @Test void should_return_204_without_body_location_or_new_token() throws Exception {
        mvc.perform(put(PATH).contentType("application/json").content(BODY).with(authentication(auth())))
                .andExpect(status().isNoContent()).andExpect(content().string("")).andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location"));
        verify(service).confirm(eq(actor),eq(session),any());
    }
    @Test void should_require_login_and_typed_context() throws Exception {
        mvc.perform(put(PATH).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var bad=auth();bad.setDetails("forged");mvc.perform(put(PATH).contentType("application/json").content(BODY).with(authentication(bad))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));verifyNoInteractions(service);
    }
    @Test void should_preserve_400_415_contracts() throws Exception {
        mvc.perform(put(PATH).contentType("text/plain").content(BODY).with(authentication(auth()))).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(put(PATH).contentType("application/json").content("{}").with(authentication(auth()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    @Test void should_map_confirmation_errors_and_429_retry_after() throws Exception {
        RuntimeException[] errors={new EmailChangeVerificationFailedException(),new EmailChangeConflictException(),new EmailChangeConfirmationRateLimitedException(900),new EmailChangeConfirmationUnavailableException(new RuntimeException("secret")),new EmailChangeEmailAlreadyExistsException(),new ReauthenticationRequiredException(),new CurrentUserRevokedException(),new IllegalStateException("Safe failure",EmailChangeDiagnostics.sanitized(null))};
        String[] codes={"EMAIL_CHANGE_VERIFICATION_FAILED","EMAIL_CHANGE_CONFLICT","EMAIL_CHANGE_CONFIRMATION_RATE_LIMITED","EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE","EMAIL_ALREADY_EXISTS","REAUTHENTICATION_REQUIRED","TOKEN_REVOKED","INTERNAL_ERROR"};int[] statuses={400,409,429,503,409,401,401,500};
        for(int i=0;i<errors.length;i++){doThrow(errors[i]).when(service).confirm(any(),any(),any());var result=mvc.perform(put(PATH).contentType("application/json").content("invalid").with(authentication(auth()))).andExpect(status().is(statuses[i])).andExpect(content().contentType("application/problem+json")).andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT))).andExpect(content().string(not(containsString("secret"))));if(statuses[i]==429)result.andExpect(header().string("Retry-After","900"));}
    }
    @Test void should_log_only_safe_diagnostics() throws Exception {
        var logger=(Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{var cause=new RuntimeException("private@example.com 012345 hidden-token",new RuntimeException("secret"));cause.addSuppressed(new RuntimeException("secret"));doThrow(new EmailChangeConfirmationUnavailableException(cause)).when(service).confirm(any(),any(),any());mvc.perform(put(PATH).contentType("application/json").content(BODY).with(authentication(auth()))).andExpect(status().isServiceUnavailable());assertFalse(appender.list.isEmpty());for(var event:appender.list){assertFalse(event.getFormattedMessage().contains("private@"));assertNull(event.getThrowableProxy().getCause());assertFalse(event.getThrowableProxy().getMessage().contains("secret"));}}
        finally{logger.detachAppender(appender);appender.stop();}
    }
}
