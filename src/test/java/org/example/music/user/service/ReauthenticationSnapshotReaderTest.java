package org.example.music.user.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.example.music.user.domain.*;
import org.example.music.user.exception.*;
import org.example.music.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.*;

class ReauthenticationSnapshotReaderTest {
    final UserRepository users=mock(UserRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus transaction=mock(TransactionStatus.class);
    final ReauthenticationSnapshotReader reader=new ReauthenticationSnapshotReader(users,manager);
    @Test void should_load_five_fields_in_read_only_transaction_and_hide_sensitive_values(){
        when(manager.getTransaction(any())).thenAnswer(i->{assertTrue(((TransactionDefinition)i.getArgument(0)).isReadOnly());return transaction;});
        var row=new UserReauthenticationSnapshot(42,"alice@example.com","secret-hash",UserRole.ADMIN,7);
        when(users.findReauthenticationSnapshot(42)).thenReturn(Optional.of(row));
        assertEquals(row,reader.load(42));verify(manager).commit(transaction);assertEquals(5,UserReauthenticationSnapshot.class.getRecordComponents().length);
        assertFalse(row.toString().contains("alice@example.com"));assertFalse(row.toString().contains("secret-hash"));
    }
    @Test void should_translate_start_query_and_commit_failure(){
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("secret"));
        assertThrows(ReauthenticationUnavailableException.class,()->reader.load(42));
        doReturn(transaction).when(manager).getTransaction(any());when(users.findReauthenticationSnapshot(42)).thenThrow(new DataAccessResourceFailureException("secret"));
        assertThrows(ReauthenticationUnavailableException.class,()->reader.load(42));
        doReturn(Optional.of(new UserReauthenticationSnapshot(42,null,"hash",UserRole.USER,0))).when(users).findReauthenticationSnapshot(42);
        doThrow(new TransactionSystemException("secret")).when(manager).commit(transaction);
        assertThrows(ReauthenticationUnavailableException.class,()->reader.load(42));
    }
    @Test void should_revoke_missing_account_and_reject_corrupt_security_fields(){
        when(manager.getTransaction(any())).thenReturn(transaction);when(users.findReauthenticationSnapshot(42)).thenReturn(Optional.empty());
        assertThrows(CurrentUserRevokedException.class,()->reader.load(42));
        for(var row:new UserReauthenticationSnapshot[]{new UserReauthenticationSnapshot(43,null,"hash",UserRole.USER,0),new UserReauthenticationSnapshot(42,null,"",UserRole.USER,0),new UserReauthenticationSnapshot(42,null,"hash",null,0),new UserReauthenticationSnapshot(42,null,"hash",UserRole.USER,-1)}){
            when(users.findReauthenticationSnapshot(42)).thenReturn(Optional.of(row));assertThrows(ReauthenticationUnavailableException.class,()->reader.load(42));
        }
    }
}
