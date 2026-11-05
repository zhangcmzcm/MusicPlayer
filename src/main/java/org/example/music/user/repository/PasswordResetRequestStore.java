package org.example.music.user.repository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.*;
import org.springframework.stereotype.Repository;
@Repository
public class PasswordResetRequestStore {
 public static final String PREFIX="music:auth:password-reset:";
 private static final RedisScript<List> ADMIT=script("admit");
 private final StringRedisTemplate redis;
 public PasswordResetRequestStore(StringRedisTemplate redis){this.redis=redis;}
 private static RedisScript<List> script(String name){
  try{return new DefaultRedisScript<>(resource("common")+"\n"+resource(name),List.class);}
  catch(IOException failure){throw new ExceptionInInitializerError("Password reset Redis resources unavailable");}
 }
 private static String resource(String name)throws IOException {
  try(var input=new ClassPathResource("redis/password-reset-request/"+name+".lua").getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
 }
 private List<?> execute(RedisScript<List> script,List<String> keys,Object... args){
  try{return redis.execute(script,keys,args);}
  catch(RuntimeException failure){throw new PasswordResetRequestUnavailableException(failure);}
 }
 public PasswordResetAdmission admit(String emailDigest,String ipDigest){
  if(!PasswordResetIdentifiers.isCanonicalDigest(emailDigest)||!PasswordResetIdentifiers.isCanonicalDigest(ipDigest))throw unavailable();
  var reply=execute(ADMIT,List.of(PREFIX+"email-cooldown:"+emailDigest,PREFIX+"email-daily:"+emailDigest,PREFIX+"ip:"+ipDigest));
  if(reply!=null&&reply.size()==3&&reply.stream().allMatch(Long.class::isInstance)){
   long status=(Long)reply.get(0),first=(Long)reply.get(1),last=(Long)reply.get(2);
   if(status==1&&first>=1&&first<=86400&&last==0)throw new PasswordResetRequestRateLimitedException(first);
   if(status==0&&first>=0&&first<=9007199254440991L&&last==first+300000)return new PasswordResetAdmission(Instant.ofEpochMilli(first),Instant.ofEpochMilli(last));
  }
  throw unavailable();
 }
 private static final RedisScript<List> ENQUEUE=script("enqueue"),CLAIM=script("claim"),PREPARE=script("prepare"),ACTIVATE=script("activate"),FINISH=script("finish"),REAP=script("reap");
 private static final List<String> QUEUE_KEYS=List.of(PREFIX+"ready",PREFIX+"outstanding");
 public void enqueue(PasswordResetTask task,int capacity){
  var args=new ArrayList<Object>(arguments(task));args.add(Integer.toString(capacity));result(execute(ENQUEUE,QUEUE_KEYS,args.toArray()),false);
 }
 public Optional<PasswordResetClaim> claim(String ownerNonce){
  if(!PasswordResetIdentifiers.isCanonicalDigest(ownerNonce))throw unavailable();
  var reply=execute(CLAIM,QUEUE_KEYS,ownerNonce);
  if(reply!=null&&reply.size()==1&&Long.valueOf(1).equals(reply.getFirst()))return Optional.empty();
  if(reply!=null&&reply.size()==13&&Long.valueOf(0).equals(reply.getFirst())&&reply.subList(1,13).stream().allMatch(String.class::isInstance)){
   try{
    String digest=(String)reply.get(1),emailDigest=(String)reply.get(2),email=(String)reply.get(3);
    long admitted=number((String)reply.get(4)),expires=number((String)reply.get(5));
    Optional<PasswordResetAccountSnapshot> account;
    if("1".equals(reply.get(6))){
     long userId=number((String)reply.get(7)),version=number((String)reply.get(10));
     if(userId<1||userId>Integer.MAX_VALUE)throw unavailable();
     account=Optional.of(new PasswordResetAccountSnapshot((int)userId,(String)reply.get(8),UserRole.valueOf((String)reply.get(9)),version));
    }else if("0".equals(reply.get(6))&&"".equals(reply.get(7))&&"".equals(reply.get(8))&&"".equals(reply.get(9))&&"".equals(reply.get(10)))account=Optional.empty();
    else throw unavailable();
    if(!ownerNonce.equals(reply.get(12)))throw unavailable();
    var task=new PasswordResetTask(digest,emailDigest,email,new PasswordResetAdmission(Instant.ofEpochMilli(admitted),Instant.ofEpochMilli(expires)),account,(String)reply.get(11));
    arguments(task);return Optional.of(new PasswordResetClaim(task,ownerNonce));
   }catch(RuntimeException failure){throw new PasswordResetRequestUnavailableException(failure);}
  }
  throw unavailable();
 }
 public boolean prepare(PasswordResetClaim claim,String codeHash){
  var args=claimArguments(claim);args.add(codeHash);return result(execute(PREPARE,QUEUE_KEYS,args.toArray()),true);
 }
 public boolean activate(PasswordResetClaim claim){return result(execute(ACTIVATE,QUEUE_KEYS,claimArguments(claim).toArray()),true);}
 public void finish(PasswordResetClaim claim){result(execute(FINISH,QUEUE_KEYS,claimArguments(claim).toArray()),false);}
 public int reap(int batchSize){
  var reply=execute(REAP,QUEUE_KEYS,Integer.toString(batchSize));
  if(reply!=null&&reply.size()==2&&Long.valueOf(0).equals(reply.get(0))&&reply.get(1) instanceof Long count&&count>=0&&count<=batchSize)return count.intValue();
  throw unavailable();
 }
 private ArrayList<Object> claimArguments(PasswordResetClaim claim){
  if(claim==null||!PasswordResetIdentifiers.isCanonicalDigest(claim.ownerNonce()))throw unavailable();
  var args=new ArrayList<Object>(arguments(claim.task()));args.add(claim.ownerNonce());return args;
 }
 private List<Object> arguments(PasswordResetTask task){
  try{
   if(task==null||!PasswordResetIdentifiers.isCanonicalDigest(task.challengeDigest())||!PasswordResetIdentifiers.isCanonicalDigest(task.emailDigest())
    ||task.email()==null||task.email().isBlank()||task.email().length()>120||task.email().indexOf('\r')>=0||task.email().indexOf('\n')>=0
    ||!PasswordResetIdentifiers.digest(task.email()).equals(task.emailDigest())||task.admission()==null||task.account()==null
    ||task.traceId()==null||!task.traceId().matches("[A-Za-z0-9._-]{1,64}"))throw unavailable();
   long first=task.admission().admittedAt().toEpochMilli(),last=task.admission().expiresAt().toEpochMilli();
   if(first<0||first>9007199254440991L||last!=first+300000)throw unavailable();
   var a=task.account().orElse(null);
   if(a!=null&&(a.userId()==null||a.userId()<1||a.email()==null||a.email().isBlank()||a.email().length()>120||a.userRole()==null||a.tokenVersion()<0))throw unavailable();
   return List.of(task.challengeDigest(),task.emailDigest(),task.email(),Long.toString(first),Long.toString(last),a==null?"0":"1",a==null?"":a.userId().toString(),a==null?"":a.email(),a==null?"":a.userRole().name(),a==null?"":Long.toString(a.tokenVersion()),task.traceId());
  }catch(RuntimeException failure){throw new PasswordResetRequestUnavailableException(failure);}
 }
 private static long number(String value){
  if(!value.matches("0|[1-9][0-9]*"))throw unavailable();return Long.parseLong(value);
 }
 private boolean result(List<?> reply,boolean staleAllowed){
  if(reply!=null&&reply.size()==1){if(Long.valueOf(0).equals(reply.getFirst()))return true;if(staleAllowed&&Long.valueOf(1).equals(reply.getFirst()))return false;}
  throw unavailable();
 }
 private static PasswordResetRequestUnavailableException unavailable(){return new PasswordResetRequestUnavailableException(null);}
}
