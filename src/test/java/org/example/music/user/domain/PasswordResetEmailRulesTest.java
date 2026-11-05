package org.example.music.user.domain;
import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
class PasswordResetEmailRulesTest {
 @Test void should_match_canonical_case_but_not_accent_or_missing_address() {
  try(var factory=Validation.buildDefaultValidatorFactory()) {
   var rules=new PasswordResetEmailRules(factory.getValidator());
   assertEquals("alice@example.com",rules.normalize(" Alice@Example.com "));
   assertTrue(rules.matches("alice@example.com","ALICE@example.com"));
   assertFalse(rules.matches("alice@example.com","álice@example.com"));
   assertFalse(rules.matches("alice@example.com",null));
   assertFalse(rules.matches("alice@example.com","   "));
  }
 }
}
