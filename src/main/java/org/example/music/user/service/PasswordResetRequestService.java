package org.example.music.user.service;
import java.util.UUID;
import org.example.music.user.config.PasswordResetProperties;
import org.example.music.user.domain.*;
import org.example.music.user.dto.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.springframework.stereotype.Service;
@Service
public class PasswordResetRequestService {
 private final PasswordResetRequestStore store;private final PasswordResetSnapshotReader snapshots;private final PasswordResetProperties properties;
 public PasswordResetRequestService(PasswordResetRequestStore store,PasswordResetSnapshotReader snapshots,PasswordResetProperties properties){this.store=store;this.snapshots=snapshots;this.properties=properties;}
 public PasswordResetRequestResponse request(PasswordResetRequest input,String clientIp,String traceId){
  try{
   String emailDigest=PasswordResetIdentifiers.digest(input.email());var admission=store.admit(emailDigest,PasswordResetIdentifiers.digest(clientIp));
   var account=snapshots.capture(input.email());String id=PasswordResetIdentifiers.generate();
   String safeTrace=traceId!=null&&traceId.matches("[A-Za-z0-9._-]{1,64}")?traceId:UUID.randomUUID().toString();
   store.enqueue(new PasswordResetTask(PasswordResetIdentifiers.digest(id),emailDigest,input.email(),admission,account,safeTrace),properties.getQueueCapacity());
   return new PasswordResetRequestResponse(id,admission.expiresAt());
  }catch(PasswordResetRequestRateLimitedException|PasswordResetRequestUnavailableException known){throw known;}
  catch(RuntimeException failure){throw new IllegalStateException("Password reset request failed unexpectedly",PasswordResetDiagnostics.sanitized(failure));}
 }
}
