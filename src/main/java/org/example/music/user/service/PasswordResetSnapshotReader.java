package org.example.music.user.service;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.repository.UserRepository;
import org.example.music.user.exception.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class PasswordResetSnapshotReader {
 private final UserRepository users; private final PasswordResetEmailRules rules;private final TransactionTemplate tx;
 public PasswordResetSnapshotReader(UserRepository users,PlatformTransactionManager manager,PasswordResetEmailRules rules){
  this.users=users;this.rules=rules;tx=new TransactionTemplate(manager);tx.setReadOnly(true);
 }
 public Optional<PasswordResetAccountSnapshot> capture(String email){
  try{return tx.execute(status->users.findPasswordResetSnapshotByEmail(email).filter(row->{validate(row);return rules.matches(email,row.email());}));}
  catch(DataAccessException|TransactionException|PasswordResetValidationException failure){throw new PasswordResetRequestUnavailableException(failure);}
 }
 public boolean stillMatches(PasswordResetAccountSnapshot expected,String email){
  try{return Boolean.TRUE.equals(tx.execute(status->users.findPasswordResetSnapshotById(expected.userId()).filter(row->{validate(row);return row.equals(expected)&&rules.matches(email,row.email());}).isPresent()));}
  catch(DataAccessException|TransactionException|PasswordResetValidationException failure){throw new PasswordResetRequestUnavailableException(failure);}
 }
 private void validate(PasswordResetAccountSnapshot row){
  if(row.userId()==null||row.userId()<1||row.userRole()==null||row.tokenVersion()<0||row.email()==null||row.email().isBlank())throw new PasswordResetRequestUnavailableException(null);
  rules.normalize(row.email());
 }
}
