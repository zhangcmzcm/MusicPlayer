package org.example.music.user.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.config.PasswordResetProperties;
import org.example.music.user.repository.PasswordResetRequestStore;
import org.junit.jupiter.api.Test;
class PasswordResetWorkerLifecycleTest {
 @Test void should_continue_after_one_iteration_failure_and_stop_receiving_work()throws Exception{
  var p=new PasswordResetProperties();p.setWorkerConcurrency(1);var processor=mock(PasswordResetTaskProcessor.class);var store=mock(PasswordResetRequestStore.class);var reached=new CountDownLatch(1);var calls=new AtomicInteger();
  when(processor.processNext()).thenAnswer(i->{if(calls.incrementAndGet()==1)throw new IllegalStateException("sensitive");reached.countDown();return false;});
  var lifecycle=new PasswordResetWorkerLifecycle(processor,store,p);
  try{lifecycle.start();lifecycle.start();assertTrue(lifecycle.isRunning());assertTrue(reached.await(5,TimeUnit.SECONDS));}finally{lifecycle.stop();}
  assertFalse(lifecycle.isRunning());assertTrue(calls.get()>=2);
 }
}
