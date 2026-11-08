package org.example.music.user.domain;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.validation.Validation;
import java.time.Instant;
import org.example.music.user.exception.PasswordResetVerificationUnavailableException;
import org.junit.jupiter.api.*;

class PasswordResetTicketRulesTest {
    static final jakarta.validation.ValidatorFactory VALIDATORS=Validation.buildDefaultValidatorFactory();
    final PasswordResetTicketRules rules=new PasswordResetTicketRules(new PasswordResetEmailRules(VALIDATORS.getValidator()));
    PasswordResetTicketSnapshot ticket(String email,String rawEmail,long version,PasswordResetTicketPeriod period) {
        return new PasswordResetTicketSnapshot("A".repeat(43),PasswordResetIdentifiers.digest(email),email,
                new PasswordResetAccountSnapshot(42,rawEmail,UserRole.ADMIN,version),period);
    }
    PasswordResetTicketPeriod period() { return new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)); }
    @AfterAll static void close() { VALIDATORS.close(); }
    @Test void should_preserve_raw_email_long_max_and_redact() {
        var value=ticket("alice@example.com","ALICE@example.com",Long.MAX_VALUE,period());
        assertDoesNotThrow(()->rules.validate(value)); assertEquals("ALICE@example.com",value.account().email());
        assertFalse(value.toString().contains("alice")); assertFalse(period().toString().contains("1970"));
        assertDoesNotThrow(()->rules.validate(ticket("alice@example.com","alice@example.com",0,period())));
    }
    @Test void should_reject_invalid_email_period_or_version() {
        for(var value:new PasswordResetTicketSnapshot[]{ticket("ALICE@example.com","ALICE@example.com",0,period()),
                ticket("alice@example.com","other@example.com",0,period()),ticket("alice@example.com","alice@example.com",-1,period()),
                ticket("alice@example.com","alice@example.com",0,new PasswordResetTicketPeriod(Instant.EPOCH,Instant.EPOCH.plusSeconds(299))),
                ticket("alice@example.com","alice@example.com",0,new PasswordResetTicketPeriod(Instant.EPOCH.plusNanos(1),Instant.EPOCH.plusSeconds(300).plusNanos(1)))}) {
            assertThrows(PasswordResetVerificationUnavailableException.class,()->rules.validate(value));
        }
    }
    @Test void should_reject_bad_id_role_digest_and_missing_period() {
        for(var account:new PasswordResetAccountSnapshot[]{new PasswordResetAccountSnapshot(0,"a@b.com",UserRole.USER,0),
                new PasswordResetAccountSnapshot(42,"a@b.com",null,0)}) {
            assertThrows(PasswordResetVerificationUnavailableException.class,()->rules.validate(new PasswordResetTicketSnapshot("A".repeat(43),PasswordResetIdentifiers.digest("a@b.com"),"a@b.com",account,period())));
        }
        var value=ticket("a@b.com","a@b.com",0,period());
        assertThrows(PasswordResetVerificationUnavailableException.class,()->rules.validate(new PasswordResetTicketSnapshot(value.digest(),"B".repeat(43),value.email(),value.account(),period())));
        assertThrows(PasswordResetVerificationUnavailableException.class,()->rules.validate(ticket("a@b.com","a@b.com",0,null)));
    }
}
