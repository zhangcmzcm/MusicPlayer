package org.example.music.user.service;
import java.util.Map;
import org.example.music.common.util.SecureVerificationCodeGenerator;
import org.example.music.security.PasswordHasher;
import org.example.music.user.domain.*;
import org.example.music.user.email.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.slf4j.*;
import org.springframework.stereotype.Component;
@Component
public class PasswordResetTaskProcessor {
 private static final Logger LOG=LoggerFactory.getLogger(PasswordResetTaskProcessor.class);
 private final PasswordResetRequestStore store;private final PasswordResetSnapshotReader snapshots;
 private final PasswordHasher hasher;private final SecureVerificationCodeGenerator codes;private final BoundedPasswordResetEmailDelivery delivery;
 public PasswordResetTaskProcessor(PasswordResetRequestStore store,PasswordResetSnapshotReader snapshots,PasswordHasher hasher,SecureVerificationCodeGenerator codes,BoundedPasswordResetEmailDelivery delivery){this.store=store;this.snapshots=snapshots;this.hasher=hasher;this.codes=codes;this.delivery=delivery;}
 public boolean processNext(){
  var next=store.claim(PasswordResetIdentifiers.generate());if(next.isEmpty())return false;
  var claim=next.get();var task=claim.task();Map<String,String> previous=MDC.getCopyOfContextMap();
  try{
   MDC.put("traceId",task.traceId());
   if(task.account().isEmpty()||Thread.currentThread().isInterrupted())return true;
   var expected=task.account().orElseThrow();if(!snapshots.stillMatches(expected,task.email()))return true;
   String code=asciiCode(codes.generate());String hash=hasher.hash(code);
   if(Thread.currentThread().isInterrupted()||!store.prepare(claim,hash))return true;
   delivery.send(new PasswordResetMessage(task.email(),code,task.admission().expiresAt()));
   if(!Thread.currentThread().isInterrupted()&&snapshots.stillMatches(expected,task.email()))store.activate(claim);
  }catch(RuntimeException failure){
   LOG.warn("Password reset task ended without confirmation, traceId={}, exceptionType={}",MDC.get("traceId"),failure.getClass().getSimpleName(),PasswordResetDiagnostics.sanitized(failure));
  }finally{
   try{store.finish(claim);}catch(RuntimeException failure){LOG.warn("Password reset task cleanup unconfirmed, traceId={}, exceptionType={}",MDC.get("traceId"),failure.getClass().getSimpleName(),PasswordResetDiagnostics.sanitized(failure));}
   if(previous==null)MDC.clear();else MDC.setContextMap(previous);
  }
  return true;
 }
 private static String asciiCode(String generated){
  if(generated==null||generated.length()!=6)throw new IllegalStateException("Invalid code generator output");
  var out=new StringBuilder(6);for(int i=0;i<6;i++){int d=Character.digit(generated.charAt(i),10);if(d<0)throw new IllegalStateException("Invalid code generator output");out.append((char)('0'+d));}return out.toString();
 }
}
