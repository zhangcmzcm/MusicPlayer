package org.example.music.user.email;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.music.user.exception.*;
import org.junit.jupiter.api.Test;

class BoundedReauthenticationEmailDeliveryTest {
    final ReauthenticationEmailMessage message=new ReauthenticationEmailMessage("alice@example.com","012345",Instant.now());
    @Test void should_keep_four_uninterruptible_workers_occupied_after_caller_timeout() throws Exception {
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);var exited=new CountDownLatch(4);
        ReauthenticationEmailSender sender = m -> {
            entered.countDown();
            boolean done = false;
            try {
                while (!done) {
                    try {
                        done = release.await(10, TimeUnit.SECONDS);
                        if (!done) throw new AssertionError("release timed out");
                    } catch (InterruptedException ignored) {
                        // 模拟不响应取消的SMTP调用，任务退出前必须持续占用物理槽位。
                    }
                }
            } finally { exited.countDown(); }
        };
        var workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),new ThreadPoolExecutor.AbortPolicy());
        var delivery=new BoundedReauthenticationEmailDelivery(sender,workers,Duration.ofMillis(500));
        var callers=Executors.newFixedThreadPool(4);List<Future<?>> results=new ArrayList<>();
        try {
            for(int i=0;i<4;i++)results.add(callers.submit(()->assertThrows(ReauthenticationEmailSendFailedException.class,()->delivery.send(message))));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->delivery.send(message));
            for(var result:results)result.get(5,TimeUnit.SECONDS);
            assertEquals(4,workers.getActiveCount()); assertEquals(4,workers.getPoolSize());assertTrue(workers.getQueue().isEmpty());
            assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->delivery.send(message));
        } finally {release.countDown();assertTrue(exited.await(5,TimeUnit.SECONDS));delivery.close();callers.shutdownNow();assertTrue(callers.awaitTermination(5,TimeUnit.SECONDS));assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_restore_interruption_and_cancel_waiting_send() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new AtomicBoolean();
        var worker=Executors.newSingleThreadExecutor();var delivery=new BoundedReauthenticationEmailDelivery(m->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}},worker,Duration.ofSeconds(10));
        var thread=new Thread(()->{Thread.currentThread().interrupt();assertThrows(ReauthenticationEmailSendFailedException.class,()->delivery.send(message));interrupted.set(Thread.currentThread().isInterrupted());});
        try{thread.start();thread.join(5000);assertFalse(thread.isAlive());assertTrue(interrupted.get());}finally{release.countDown();delivery.close();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void should_preserve_known_failure_and_sanitize_unexpected_failure() {
        var worker=Executors.newSingleThreadExecutor();var delivery=new BoundedReauthenticationEmailDelivery(m->{throw new RuntimeException("secret recipient code");},worker,Duration.ofSeconds(2));
        try{var error=assertThrows(IllegalStateException.class,()->delivery.send(message));assertFalse(error.getCause().getMessage().contains("secret"));assertNull(error.getCause().getCause());}finally{delivery.close();}
    }
    @Test void should_stop_admission_after_shutdown() {
        var delivery=new BoundedReauthenticationEmailDelivery(m->{});delivery.close();assertThrows(ReauthenticationEmailRequestUnavailableException.class,()->delivery.send(message));
    }
}
