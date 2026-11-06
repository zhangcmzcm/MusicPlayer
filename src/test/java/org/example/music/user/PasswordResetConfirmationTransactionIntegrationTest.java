package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.*;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class PasswordResetConfirmationTransactionIntegrationTest extends PasswordResetConfirmationIntegrationSupport {
    @Test void should_update_only_password_version_and_timestamp_and_preserve_concurrent_username() {
        var user=save("ALICE@example.com",UserRole.USER);
        var expected=users.findPasswordResetPreparedSnapshot(user.getId()).orElseThrow();
        jdbc.update("update users set username='renamed' where id=?",user.getId()); var before=row(user);
        String hash=hasher.hash("new-password"); updater.update(expected,hash); var after=row(user);
        assertEquals(hash,after.get("password_token")); assertEquals(1L,((Number)after.get("token_version")).longValue());
        for(String key:before.keySet()) if(!Set.of("password_token","token_version","updated_at").contains(key)) assertEquals(before.get(key),after.get(key),key);
    }
    @ParameterizedTest @ValueSource(strings={"email","role","hash","version","delete"})
    void should_reject_every_changed_binary_snapshot_without_retry(String change) {
        var user=save("ALICE@example.com",UserRole.ADMIN); var expected=users.findPasswordResetPreparedSnapshot(user.getId()).orElseThrow();
        switch(change) {
            case "email" -> jdbc.update("update users set email='alice@example.com' where id=?",user.getId());
            case "role" -> jdbc.update("update users set user_role='USER' where id=?",user.getId());
            case "hash" -> jdbc.update("update users set password_token=? where id=?",expected.passwordHash().replace("$2a$","$2b$"),user.getId());
            case "version" -> jdbc.update("update users set token_version=1 where id=?",user.getId());
            case "delete" -> jdbc.update("delete from users where id=?",user.getId());
        }
        assertThrows(PasswordResetVerificationFailedException.class,()->updater.update(expected,hasher.hash("new-password")));
        verify(users,times(1)).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
    }
    @Test void should_rollback_actual_sql_on_commit_failure() {
        var user=save("a@example.com",UserRole.USER); var expected=users.findPasswordResetPreparedSnapshot(user.getId()).orElseThrow(); var before=row(user);
        doAnswer(i->{ transactions.rollback(i.getArgument(0)); throw new TransactionSystemException("lost"); }).when(transactions).commit(any());
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->updater.update(expected,hasher.hash("new-password")));
        doCallRealMethod().when(transactions).commit(any()); assertEquals(before,row(user));
    }
    @Test void should_report_unknown_after_actual_commit_without_retry() {
        var user=save("a@example.com",UserRole.USER); var expected=users.findPasswordResetPreparedSnapshot(user.getId()).orElseThrow(); String hash=hasher.hash("new-password");
        doAnswer(i->{ i.callRealMethod(); throw new TransactionSystemException("lost"); }).when(transactions).commit(any());
        assertThrows(PasswordResetConfirmationUnavailableException.class,()->updater.update(expected,hash));
        doCallRealMethod().when(transactions).commit(any()); assertEquals(hash,row(user).get("password_token"));
        assertEquals(1L,((Number)row(user).get("token_version")).longValue());
        verify(users,times(1)).resetPasswordIfSnapshotMatches(anyInt(),any(),anyString(),anyString(),anyLong(),anyString());
    }
}
