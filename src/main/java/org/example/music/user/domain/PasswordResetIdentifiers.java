package org.example.music.user.domain;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
public final class PasswordResetIdentifiers {
 private static final SecureRandom RANDOM=new SecureRandom();
 private PasswordResetIdentifiers(){}
 public static String generate(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return encode(bytes);}
 public static String digest(String value){
  try{return encode(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException failure){throw new IllegalStateException("Required digest unavailable");}
 }
 public static boolean isCanonicalDigest(String value){
  if(value==null||!value.matches("[A-Za-z0-9_-]{43}"))return false;
  try{byte[] bytes=Base64.getUrlDecoder().decode(value);return bytes.length==32&&encode(bytes).equals(value);}
  catch(IllegalArgumentException failure){return false;}
 }
 private static String encode(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
