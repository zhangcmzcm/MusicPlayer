package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.*;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class EmailChangeConfirmationTransactionIntegrationTest extends EmailChangeConfirmationIntegrationSupport {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "," Old@Example.com "})
    void should_update_raw_email_and_version_atomically_without_overwriting_other_fields(String email){
        var user=save(UserRole.USER,email);var before=row(user);var s=session(user);var expected=reader.load(actor(user),s);
        updater.update(actor(user),s,expected,"new@example.com");var after=row(user);
        assertEquals("new@example.com",after.get("email"));assertEquals(1L,((Number)after.get("token_version")).longValue());assertNotNull(after.get("updated_at"));
        for(String key:before.keySet())if(!Set.of("email","token_version","updated_at").contains(key))assertEquals(before.get(key),after.get(key),key);
        assertThrows(CurrentUserRevokedException.class,()->updater.update(actor(user),s,expected,"another@example.com"));
    }
    @Test void should_reject_binary_snapshot_change_and_preserve_concurrent_username(){
        var user=save(UserRole.USER,"Old@Example.com");var s=session(user);var expected=reader.load(actor(user),s);
        jdbc.update("update users set email='old@example.com' where id=?",user.getId());assertThrows(EmailChangeConflictException.class,()->updater.update(actor(user),s,expected,"new@example.com"));
        var current=reader.load(actor(user),s);jdbc.update("update users set username='changed-name' where id=?",user.getId());updater.update(actor(user),s,current,"new@example.com");assertEquals("changed-name",row(user).get("username"));
    }
    @Test void should_map_actual_mysql_email_unique_conflict_and_roll_back_every_column(){
        var user=save(UserRole.USER,"old@example.com");save(UserRole.USER,"résumé@example.com");var before=row(user);var s=session(user);var expected=reader.load(actor(user),s);
        assertThrows(EmailChangeEmailAlreadyExistsException.class,()->updater.update(actor(user),s,expected,"resume@example.com"));assertEquals(before,row(user));
    }
    @Test void should_refuse_max_version_without_overflow(){
        var user=save(UserRole.USER,null);jdbc.update("update users set token_version=? where id=?",Long.MAX_VALUE,user.getId());var s=session(user);var expected=reader.load(actor(user),s);
        assertThrows(EmailChangeConfirmationUnavailableException.class,()->updater.update(actor(user),s,expected,"new@example.com"));assertEquals(Long.MAX_VALUE,((Number)row(user).get("token_version")).longValue());
    }
    @Test void should_report_unknown_after_real_commit_without_claiming_rollback(){
        var user=save(UserRole.USER,"old@example.com");var s=session(user);var expected=reader.load(actor(user),s);
        doAnswer(i->{i.callRealMethod();throw new TransactionSystemException("confirmation lost");}).when(transactions).commit(any());
        assertThrows(EmailChangeConfirmationUnavailableException.class,()->updater.update(actor(user),s,expected,"new@example.com"));assertEquals("new@example.com",row(user).get("email"));assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }
}
