package org.example.music.storage.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class StreamReadConfigTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(StorageConfig.class)
            .withPropertyValues("app.storage.endpoint=http://localhost:1","app.storage.region=us-east-1",
                    "app.storage.access-key=test-access-key","app.storage.secret-key=test-secret-key",
                    "app.storage.connect-timeout=2s","app.storage.read-timeout=30s");
    @Test
    void should_keep_legacy_budget_and_separate_stream_client() {
        runner.run(context->{
            assertNull(context.getStartupFailure());
            assertEquals(50,context.getBean(StreamReadProperties.class).maxConnections());
            var legacy=context.getBean("s3Client",S3Client.class);var stream=context.getBean("streamReadS3Client",S3Client.class);
            assertSame(legacy,context.getBean(S3Client.class));assertNotSame(legacy,stream);
            assertEquals(Duration.ofSeconds(2),legacy.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout().orElseThrow());
            assertEquals(Duration.ofSeconds(30),legacy.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().orElseThrow());
            assertTrue(stream.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().isEmpty());
            assertTrue(stream.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout().isEmpty());
            assertEquals(0,stream.serviceClientConfiguration().overrideConfiguration().retryPolicy().orElseThrow().numRetries());
        });
    }
    @Test
    void should_reject_unbounded_or_invalid_pool_configuration() {
        runner.withPropertyValues("app.storage.stream.max-connections=0").run(context->assertNotNull(context.getStartupFailure()));
    }
}
