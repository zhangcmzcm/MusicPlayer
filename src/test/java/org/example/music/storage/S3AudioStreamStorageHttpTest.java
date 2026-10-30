package org.example.music.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.exception.AudioStreamInconsistentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Timeout(15)
class S3AudioStreamStorageHttpTest {
    private HttpServer server;
    private S3Client client;
    private S3AudioStreamStorage storage;
    private volatile String mode="FULL";
    private final CountDownLatch release=new CountDownLatch(1);
    private final byte[] bytes="0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final AudioObjectMetadata expected=new AudioObjectMetadata("bucket","key","audio/mpeg",10,"a".repeat(64));
    @BeforeEach void start() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            boolean range=exchange.getRequestHeaders().getFirst("Range")!=null;
            byte[] body=range?Arrays.copyOfRange(bytes,2,5):bytes;
            if(mode.equals("CANCEL")) { body=new byte[1024*1024];Arrays.fill(body,(byte)'0'); }
            if(mode.equals("IGNORE")) { body=bytes;range=false; }
            exchange.getResponseHeaders().set("Content-Type",mode.equals("MIME")?"audio/flac":"audio/mpeg");
            exchange.getResponseHeaders().set("x-amz-meta-sha256",mode.equals("SHA")?"b".repeat(64):expected.sha256());
            if(range) { exchange.getResponseHeaders().set("Content-Range",mode.equals("RANGE")?"bytes 1-3/10":"bytes 2-4/10"); }
            exchange.sendResponseHeaders(range?206:200,mode.equals("LENGTH")?body.length+1:body.length);
            try(var output=exchange.getResponseBody()) {
                if(mode.equals("SLOW")) { output.write(body,0,1);output.flush();Thread.sleep(1500);output.write(body,1,body.length-1); }
                else if(mode.equals("CONTINUOUS")) { for(byte b:body) { output.write(b);output.flush();Thread.sleep(100); } }
                else if(mode.equals("CANCEL")) { output.write(body,0,1);output.flush();release.await(5,TimeUnit.SECONDS);output.write(body,1,body.length-1); }
                else { output.write(body); }
            } catch(IOException ignored) {
                // Client cancellation intentionally closes the socket while the fixture writes.
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        client=S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:"+server.getAddress().getPort()))
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key","test-secret-key")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(1).connectionTimeout(Duration.ofMillis(100))
                        .connectionAcquisitionTimeout(Duration.ofMillis(600)).socketTimeout(Duration.ofMillis(600)))
                .overrideConfiguration(b->b.retryPolicy(RetryPolicy.none())).build();
        storage=new S3AudioStreamStorage(client);
    }
    @AfterEach void stop() { release.countDown();if(client!=null)client.close();if(server!=null)server.stop(0); }
    @Test void should_allow_continuous_transfer_beyond_connection_budget() throws Exception {
        mode="CONTINUOUS";long start=System.nanoTime();
        try(var stream=storage.open(expected,null)) { assertArrayEquals(bytes,stream.content().readAllBytes());stream.completed(); }
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()>500);
    }
    @Test void should_timeout_on_idle_socket_after_headers() throws Exception {
        mode="SLOW";
        try(var stream=storage.open(expected,null)) { assertThrows(IOException.class,()->stream.content().readAllBytes()); }
    }
    @ParameterizedTest @ValueSource(strings={"IGNORE","RANGE","LENGTH","MIME","SHA"})
    void should_validate_actual_http_response(String bad) {
        mode=bad;assertThrows(AudioStreamInconsistentException.class,()->storage.open(expected,new AudioByteRange(2,4)));
    }
    @Test void should_abort_without_draining_and_allow_pool_reuse() throws Exception {
        mode="CANCEL";
        var large=new AudioObjectMetadata("bucket","key","audio/mpeg",1024*1024,expected.sha256());
        var stream=storage.open(large,null);assertEquals('0',stream.content().read());
        long start=System.nanoTime();stream.close();
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<500,"abort must not wait for fixture's remaining bytes");
        mode="FULL";release.countDown();
        try(var next=storage.open(expected,null)) { assertArrayEquals(bytes,next.content().readAllBytes());next.completed(); }
    }
}
