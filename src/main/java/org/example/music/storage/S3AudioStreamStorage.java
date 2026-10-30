package org.example.music.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.math.BigInteger;
import java.util.Objects;
import java.util.regex.Pattern;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.domain.AudioStreamStorage;
import org.example.music.storage.domain.VerifiedAudioStream;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.storage.exception.AudioStreamInconsistentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class S3AudioStreamStorage implements AudioStreamStorage {
    private static final Logger LOGGER=LoggerFactory.getLogger(S3AudioStreamStorage.class);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)", Pattern.CASE_INSENSITIVE);
    private final S3Client client;
    public S3AudioStreamStorage(S3Client client) { this.client=Objects.requireNonNull(client); }

    @Override
    public VerifiedAudioStream open(AudioObjectMetadata expected, AudioByteRange range) {
        var request=GetObjectRequest.builder().bucket(expected.bucket()).key(expected.objectKey());
        if (range!=null) { request.range("bytes="+range.start()+"-"+range.end()); }
        ResponseInputStream<GetObjectResponse> response;
        try { response=client.getObject(request.build()); }
        catch (S3Exception exception) {
            if (exception.statusCode()==404) { throw new AudioObjectNotFoundException(expected.bucket(),expected.objectKey()); }
            if (exception.statusCode()==416 || exception.statusCode()==412) { throw new AudioStreamInconsistentException(); }
            throw new AudioStorageUnavailableException("Audio read unavailable",null);
        } catch (SdkException exception) { throw new AudioStorageUnavailableException("Audio read unavailable",null); }
        try {
            var actual=response.response();
            long length=range==null?expected.sizeBytes():range.length();
            String sha=actual.metadata().get("sha256");
            boolean valid=actual.sdkHttpResponse().statusCode()==(range==null?200:206)
                    && Objects.equals(actual.contentLength(),length)
                    && Objects.equals(actual.contentType(),expected.contentType())
                    && sha!=null && expected.sha256().equalsIgnoreCase(sha)
                    && (actual.contentEncoding()==null || actual.contentEncoding().isBlank() || actual.contentEncoding().equalsIgnoreCase("identity"));
            if (range==null) { valid &= actual.contentRange()==null; }
            else {
                var match=CONTENT_RANGE.matcher(actual.contentRange()==null?"":actual.contentRange());
                valid &= match.matches() && new BigInteger(match.group(1)).equals(BigInteger.valueOf(range.start()))
                        && new BigInteger(match.group(2)).equals(BigInteger.valueOf(range.end()))
                        && new BigInteger(match.group(3)).equals(BigInteger.valueOf(expected.sizeBytes()));
            }
            if (!valid) { throw new AudioStreamInconsistentException(); }
            return new Handle(response,length);
        } catch (RuntimeException exception) {
            try { response.abort(); }
            catch(SdkException abortFailure) { LOGGER.warn("Audio abort failed, traceId={}",MDC.get("traceId")); }
            throw exception;
        }
    }

    private static final class Handle implements VerifiedAudioStream {
        private final ResponseInputStream<GetObjectResponse> response;
        private final long length;
        private boolean completed;
        private boolean closed;
        private Handle(ResponseInputStream<GetObjectResponse> response,long length) { this.response=response;this.length=length; }
        @Override public InputStream content() {
            return new FilterInputStream(response) {
                @Override public void close() throws IOException { Handle.this.close(); }
                @Override public int read(byte[] buffer,int offset,int length) throws IOException {
                    try { return in.read(buffer,offset,length); }
                    catch (SdkException exception) { throw new IOException("Audio read interrupted"); }
                }
                @Override public int read() throws IOException {
                    try { return in.read(); }
                    catch (SdkException exception) { throw new IOException("Audio read interrupted"); }
                }
            };
        }
        @Override public long contentLength() { return length; }
        @Override public void completed() { completed=true; }
        @Override public void close() throws IOException {
            if (closed) { return; }
            closed=true;
            try { if (completed) { response.close(); } else { response.abort(); } }
            catch (SdkException exception) { throw new IOException("Audio stream close failed"); }
        }
    }
}
