package org.example.music.user.email;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.music.user.exception.ReauthenticationEmailDiagnostics;
import org.example.music.user.exception.ReauthenticationEmailRequestUnavailableException;
import org.example.music.user.exception.ReauthenticationEmailSendFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BoundedReauthenticationEmailDelivery {
    private final ReauthenticationEmailSender sender;
    private final ExecutorService executor;
    private final Duration wait;

    @Autowired
    public BoundedReauthenticationEmailDelivery(ReauthenticationEmailSender sender) {
        this(sender, new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                threadFactory(), new ThreadPoolExecutor.AbortPolicy()), Duration.ofSeconds(20));
    }

    BoundedReauthenticationEmailDelivery(ReauthenticationEmailSender sender, ExecutorService executor, Duration wait) {
        this.sender = sender;
        this.executor = executor;
        this.wait = wait;
    }

    private static ThreadFactory threadFactory() {
        var sequence = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, "reauth-email-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public void send(ReauthenticationEmailMessage message) {
        final Future<?> result;
        try { result = executor.submit(() -> sender.send(message)); }
        catch (RejectedExecutionException failure) { throw new ReauthenticationEmailRequestUnavailableException(failure); }
        try { result.get(wait.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException failure) {
            result.cancel(true);
            Thread.currentThread().interrupt();
            throw new ReauthenticationEmailSendFailedException(failure);
        } catch (TimeoutException | CancellationException failure) {
            result.cancel(true);
            throw new ReauthenticationEmailSendFailedException(failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ReauthenticationEmailSendFailedException known) throw known;
            throw new IllegalStateException("Reauthentication email delivery failed unexpectedly",
                    ReauthenticationEmailDiagnostics.sanitized(failure.getCause()));
        }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
