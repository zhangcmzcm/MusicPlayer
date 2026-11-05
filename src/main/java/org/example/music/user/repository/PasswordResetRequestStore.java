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
 private static PasswordResetRequestUnavailableException unavailable(){return new PasswordResetRequestUnavailableException(null);}
}
