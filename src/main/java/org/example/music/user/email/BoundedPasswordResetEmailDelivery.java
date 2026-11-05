package org.example.music.user.email;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.exception.PasswordResetDiagnostics;
import org.example.music.user.exception.PasswordResetRequestUnavailableException;
import org.example.music.user.exception.PasswordResetSendFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BoundedPasswordResetEmailDelivery {
    private final PasswordResetEmailSender sender;
    private final ExecutorService executor;
    private final Duration wait;

    @Autowired
    public BoundedPasswordResetEmailDelivery(PasswordResetEmailSender sender, org.example.music.user.config.PasswordResetProperties properties) {
        this(sender, new ThreadPoolExecutor(properties.getWorkerConcurrency(), properties.getWorkerConcurrency(), 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                threadFactory(), new ThreadPoolExecutor.AbortPolicy()), properties.getSendTimeout());
    }

    BoundedPasswordResetEmailDelivery(PasswordResetEmailSender sender, ExecutorService executor, Duration wait) {
        this.sender = sender;
        this.executor = executor;
        this.wait = wait;
    }

    private static ThreadFactory threadFactory() {
        var sequence = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, "password-reset-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public void send(PasswordResetMessage message) {
        final Future<?> result;
        try { result = executor.submit(() -> sender.send(message)); }
        catch (RejectedExecutionException failure) { throw new PasswordResetRequestUnavailableException(failure); }
        try { result.get(wait.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException failure) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new PasswordResetSendFailedException(failure);
        } catch (TimeoutException | CancellationException failure) {
            result.cancel(true);
            throw new PasswordResetSendFailedException(failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof PasswordResetSendFailedException known) throw known;
            throw new IllegalStateException("Password reset delivery failed unexpectedly",
                    PasswordResetDiagnostics.sanitized(failure.getCause()));
        }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
