package org.example.music.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import org.example.music.user.domain.UserRole;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.*;

@org.testcontainers.junit.jupiter.EnabledIfDockerAvailable
class EmailChangeConfirmationFailureIntegrationTest extends EmailChangeConfirmationIntegrationSupport {
    @ParameterizedTest @ValueSource(strings={"admit","read","failure","consume"})
    void should_never_write_sql_when_redis_execution_confirmation_is_lost(String stage) throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var before=row(user);
        switch(stage){case "admit"->doAnswer(i->{i.callRealMethod();throw new EmailChangeConfirmationUnavailableException(null);}).when(confirmations).admit(anyInt());case "read"->doAnswer(i->{i.callRealMethod();throw new EmailChangeConfirmationUnavailableException(null);}).when(confirmations).read(anyInt(),anyString());case "failure"->doAnswer(i->{i.callRealMethod();throw new EmailChangeConfirmationUnavailableException(null);}).when(confirmations).recordFailure(any());case "consume"->doAnswer(i->{i.callRealMethod();throw new EmailChangeConfirmationUnavailableException(null);}).when(confirmations).consume(any());}
        assertCode(confirm(value,c,stage.equals("failure")?wrongCode(c):c.code()),503,"EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE");assertEquals(before,row(user));verify(users,never()).changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString());
        assertEquals("1",redis.opsForHash().get(quotaKey(user),"count"));if(stage.equals("consume"))assertTrue(active(user).isEmpty());else assertFalse(active(user).isEmpty());if(stage.equals("failure"))assertEquals("1",active(user).get("attempts"));
    }
    @Test void should_leave_code_consumed_on_database_start_failure() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var before=row(user);
        doAnswer(i->{i.callRealMethod();doThrow(new CannotCreateTransactionException("secret")).when(transactions).getTransaction(any());return null;}).when(confirmations).consume(any());
        assertCode(confirm(value,c),503,"EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE");assertTrue(active(user).isEmpty());assertEquals(before,row(user));
    }
    @Test void should_roll_back_actual_update_without_restoring_code() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var before=row(user);
        doAnswer(i->{i.callRealMethod();doAnswer(commit->{transactions.rollback(commit.getArgument(0));throw new TransactionSystemException("forced rollback before commit");}).when(transactions).commit(any());return null;}).when(confirmations).consume(any());
        assertCode(confirm(value,c),503,"EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE");doCallRealMethod().when(transactions).commit(any());
        assertEquals(before,row(user));assertTrue(active(user).isEmpty());verify(transactions).rollback(any());verify(users).changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString());assertCode(confirm(value,c),400,"EMAIL_CHANGE_VERIFICATION_FAILED");
    }
    @Test void should_keep_actual_commit_when_only_commit_confirmation_is_lost() throws Exception {
        var user=save(UserRole.USER,"old@example.com");String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");
        doAnswer(i->{i.callRealMethod();doAnswer(commit->{commit.callRealMethod();throw new TransactionSystemException("confirmation lost");}).when(transactions).commit(any());return null;}).when(confirmations).consume(any());
        assertCode(confirm(value,c),503,"EMAIL_CHANGE_CONFIRMATION_UNAVAILABLE");doCallRealMethod().when(transactions).commit(any());assertEquals("new@example.com",row(user).get("email"));assertEquals(1L,((Number)row(user).get("token_version")).longValue());assertTrue(active(user).isEmpty());assertOrdinaryAccess(value,401);
    }
    @Test void should_roll_back_unique_conflict_after_code_is_consumed() throws Exception {
        var user=save(UserRole.USER,"old@example.com");var other=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");var before=row(user);
        doAnswer(i->{i.callRealMethod();jdbc.update("update users set email='new@example.com' where id=?",other.getId());return null;}).when(confirmations).consume(any());
        assertCode(confirm(value,c),409,"EMAIL_ALREADY_EXISTS");assertEquals(before,row(user));assertTrue(active(user).isEmpty());
    }
    @Test void should_reject_real_expiry_after_hashing_without_sql() throws Exception {
        var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");
        doAnswer(i->{redis.expire(activeKey(user),Duration.ZERO);return i.callRealMethod();}).when(hasher).matches(eq(c.code()),anyString());assertCode(confirm(value,c),400,"EMAIL_CHANGE_VERIFICATION_FAILED");assertNull(row(user).get("email"));verify(users,never()).changeEmailIfSnapshotMatches(anyInt(),any(),anyLong(),anyString(),anyString());
    }
    @Test void should_not_replay_success_when_response_is_discarded() throws Exception {
        var user=save(UserRole.USER,null);String value=token(user,UserRole.USER,0L);var c=send(value,"new@example.com");confirm(value,c);
        assertEquals("new@example.com",row(user).get("email"));assertCode(confirm(value,c),401,"TOKEN_REVOKED");assertCode(confirm(token(user,UserRole.USER,0L),c),400,"EMAIL_CHANGE_VERIFICATION_FAILED");assertEquals(1L,((Number)row(user).get("token_version")).longValue());
    }
}
