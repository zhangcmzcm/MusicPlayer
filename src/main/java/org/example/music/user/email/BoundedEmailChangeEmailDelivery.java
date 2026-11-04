package org.example.music.user.email;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.exception.EmailChangeDiagnostics;
import org.example.music.user.exception.EmailChangeRequestUnavailableException;
import org.example.music.user.exception.EmailChangeSendFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BoundedEmailChangeEmailDelivery {
    private final EmailChangeEmailSender sender;
    private final ExecutorService executor;
    private final Duration wait;

    @Autowired
    public BoundedEmailChangeEmailDelivery(EmailChangeEmailSender sender) {
        this(sender, new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                threadFactory(), new ThreadPoolExecutor.AbortPolicy()), Duration.ofSeconds(20));
    }

    BoundedEmailChangeEmailDelivery(EmailChangeEmailSender sender, ExecutorService executor, Duration wait) {
        this.sender = sender;
        this.executor = executor;
        this.wait = wait;
    }

    private static ThreadFactory threadFactory() {
        var sequence = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, "email-change-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public void send(EmailChangeMessage message) {
        final Future<?> result;
        try { result = executor.submit(() -> sender.send(message)); }
        catch (RejectedExecutionException failure) { throw new EmailChangeRequestUnavailableException(failure); }
        try { result.get(wait.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException failure) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new EmailChangeSendFailedException(failure);
        } catch (TimeoutException | CancellationException failure) {
            result.cancel(true);
            throw new EmailChangeSendFailedException(failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof EmailChangeSendFailedException known) throw known;
            throw new IllegalStateException("Email change delivery failed unexpectedly",
                    EmailChangeDiagnostics.sanitized(failure.getCause()));
        }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
