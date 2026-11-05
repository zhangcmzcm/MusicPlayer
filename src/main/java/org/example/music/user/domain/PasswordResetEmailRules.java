package org.example.music.user.domain;
import jakarta.mail.internet.*;
import jakarta.validation.Validator;
import java.util.Locale;
import org.example.music.user.dto.PasswordResetRequest;
import org.example.music.user.exception.PasswordResetValidationException;
import org.springframework.stereotype.Component;
@Component
public class PasswordResetEmailRules {
 private final Validator validator;
 public PasswordResetEmailRules(Validator validator){this.validator=validator;}
 public String normalize(String raw){
  if(raw==null||raw.indexOf('\r')>=0||raw.indexOf('\n')>=0||raw.codePoints().anyMatch(p->p>=0xd800&&p<=0xdfff))throw new PasswordResetValidationException();
  String value=raw.trim().toLowerCase(Locale.ROOT);
  if(!validator.validate(new PasswordResetRequest(value)).isEmpty())throw new PasswordResetValidationException();
  try {var address=new InternetAddress(value,true);address.validate();
   if(address.getPersonal()!=null||address.isGroup()||!value.equals(address.getAddress()))throw new AddressException();
   return value;
  }catch(AddressException failure){throw new PasswordResetValidationException();}
 }
 public boolean matches(String input,String stored){return stored!=null&&!stored.isBlank()&&input.equals(normalize(stored));}
}
