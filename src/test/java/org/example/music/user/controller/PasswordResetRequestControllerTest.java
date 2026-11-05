package org.example.music.user.controller;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.config.SecurityConfig;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
@WebMvcTest(PasswordResetRequestController.class)
@Import({SecurityConfig.class,ClientIpResolver.class,PasswordResetRequestReader.class,org.example.music.user.domain.PasswordResetEmailRules.class}) @ActiveProfiles("test")
class PasswordResetRequestControllerTest {
 static final String PATH="/api/v1/users/password-reset-requests",BODY="{\"email\":\" Alice@Example.com \"}";
 @Autowired MockMvc mvc;@MockitoBean PasswordResetRequestService service;@MockitoBean UserAuthorizationStateService authorizationState;
 @BeforeEach void setup(){when(service.request(any(),anyString(),anyString())).thenReturn(new PasswordResetRequestResponse("A".repeat(43),Instant.parse("2026-11-05T00:05:00Z")));}
 @Test void should_allow_anonymous_and_take_remote_address_instead_of_forged_header()throws Exception{
  mvc.perform(post(PATH).contentType("application/json").content(BODY).header("X-Forwarded-For","forged"))
   .andExpect(status().isAccepted()).andExpect(jsonPath("$",aMapWithSize(2))).andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Location"));
  verify(service).request(eq(new PasswordResetRequest("alice@example.com")),eq("127.0.0.1"),anyString());
  mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
 }
 @Test void should_map_validation_media_type_and_business_faults()throws Exception{
  mvc.perform(post(PATH).contentType("application/json").content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  mvc.perform(post(PATH).contentType("text/plain").content(BODY)).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  RuntimeException[] errors={new PasswordResetRequestRateLimitedException(60),new PasswordResetRequestUnavailableException(new RuntimeException("secret")),new IllegalStateException("secret")};
  String[] codes={"PASSWORD_RESET_REQUEST_RATE_LIMITED","PASSWORD_RESET_REQUEST_UNAVAILABLE","INTERNAL_ERROR"};int[] statuses={429,503,500};
  for(int i=0;i<errors.length;i++){doThrow(errors[i]).when(service).request(any(),anyString(),anyString());var response=mvc.perform(post(PATH).contentType("application/json").content(BODY)).andExpect(status().is(statuses[i])).andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.type").value("urn:problem:music:"+codes[i].toLowerCase(java.util.Locale.ROOT))).andExpect(content().string(not(containsString("secret"))));if(i==0)response.andExpect(header().string("Retry-After","60"));}
 }
}
