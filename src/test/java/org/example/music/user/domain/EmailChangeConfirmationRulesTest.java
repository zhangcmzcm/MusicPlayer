package org.example.music.user.domain;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailChangeConfirmationRulesTest {
    static final ValidatorFactory FACTORY=Validation.buildDefaultValidatorFactory();
    final EmailChangeConfirmationRules rules=new EmailChangeConfirmationRules(FACTORY.getValidator());
    @AfterAll static void close(){FACTORY.close();}
    @Test void should_normalize_database_values_but_not_repair_active_binding(){
        assertNull(rules.normalizeStoredEmail(null));assertNull(rules.normalizeStoredEmail("  "));
        assertEquals("old@example.com",rules.normalizeStoredEmail(" Old@Example.com "));
        assertThrows(EmailChangeConfirmationUnavailableException.class,()->rules.requireCanonicalBinding(new EmailChangeBinding(42,"Old@Example.com","new@example.com",0)));
        assertThrows(EmailChangeConfirmationUnavailableException.class,()->rules.requireCanonicalBinding(new EmailChangeBinding(42,null," New@example.com ",0)));
        assertDoesNotThrow(()->rules.requireCanonicalBinding(new EmailChangeBinding(42,null,"new@example.com",0)));
    }
    @ParameterizedTest @ValueSource(strings={"bad","a@example.com\r\n","Name <a@example.com>","a@example.com,b@example.com"})
    void should_reject_invalid_stored_address(String value){assertThrows(EmailChangeConfirmationUnavailableException.class,()->rules.normalizeStoredEmail(value));}
    @Test void should_reject_unpaired_surrogates(){assertThrows(EmailChangeConfirmationUnavailableException.class,()->rules.normalizeStoredEmail(String.valueOf((char)0xd800)+"@example.com"));}
    @Test void should_distinguish_old_application_from_revoked_token_and_email_conflict(){
        var challenge=new EmailChangeChallengeSnapshot(new EmailChangeBinding(42,"old@example.com","new@example.com",7),"A".repeat(43),"hash",Instant.EPOCH,Instant.EPOCH.plusSeconds(300),0);
        assertDoesNotThrow(()->rules.requireBinding(challenge,new EmailChangeAccountSnapshot(42," OLD@example.com ",UserRole.USER,7),7));
        assertThrows(EmailChangeVerificationFailedException.class,()->rules.requireBinding(challenge,new EmailChangeAccountSnapshot(42,"old@example.com",UserRole.USER,8),8));
        assertThrows(CurrentUserRevokedException.class,()->rules.requireBinding(challenge,new EmailChangeAccountSnapshot(42,"old@example.com",UserRole.USER,8),7));
        assertThrows(EmailChangeConflictException.class,()->rules.requireBinding(challenge,new EmailChangeAccountSnapshot(42,null,UserRole.USER,7),7));
    }
}
