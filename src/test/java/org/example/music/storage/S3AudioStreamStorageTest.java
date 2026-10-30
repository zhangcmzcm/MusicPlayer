package org.example.music.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.argThat;

import java.util.Map;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.storage.domain.AudioObjectMetadata;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.storage.exception.AudioStreamInconsistentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3AudioStreamStorageTest {
    private final S3Client client=mock(S3Client.class);
    @SuppressWarnings("unchecked")
    private final ResponseInputStream<GetObjectResponse> response=mock(ResponseInputStream.class);
    private final S3AudioStreamStorage storage=new S3AudioStreamStorage(client);
    private final AudioObjectMetadata expected=new AudioObjectMetadata("private","secret","audio/mpeg",10,"a".repeat(64));
    @BeforeEach void setup() { when(client.getObject(any(GetObjectRequest.class))).thenReturn(response); }
    private void headers(int status,String range,long length,String mime,String sha) {
        var b=GetObjectResponse.builder().contentLength(length).contentType(mime).contentRange(range).metadata(sha==null?Map.of():Map.of("sha256",sha));
        b.sdkHttpResponse(SdkHttpResponse.builder().statusCode(status).build());when(response.response()).thenReturn(b.build());
    }
    @Test
    void should_verify_full_response_and_close_only_after_completion() throws Exception {
        headers(200,null,10,"audio/mpeg",expected.sha256().toUpperCase());
        try(var stream=storage.open(expected,null)) { assertEquals(10,stream.contentLength());stream.completed(); }
        verify(response).close();verify(response,never()).abort();
        verify(client).getObject(argThat((GetObjectRequest q)->q.range()==null));
    }
    @Test
    void should_abort_unfinished_stream_without_reading_remaining_bytes() throws Exception {
        headers(206,"bytes 2-4/10",3,"audio/mpeg",expected.sha256());
        try(var stream=storage.open(expected,new AudioByteRange(2,4))) { assertEquals(3,stream.contentLength()); }
        verify(response).abort();verify(response,never()).read(any(byte[].class),anyInt(),anyInt());verify(response,never()).close();
    }
    @ParameterizedTest
    @ValueSource(strings={"status","range","total","length","mime","sha","missing","full-not-range"})
    void should_reject_actual_bad_response_and_abort(String fault) {
        headers(fault.equals("status")||fault.equals("full-not-range")?200:206,
                fault.equals("range")?"bytes 1-3/10":fault.equals("total")?"bytes 2-4/11":fault.equals("full-not-range")?null:"bytes 2-4/10",
                fault.equals("length")?10:3,fault.equals("mime")?"audio/flac":"audio/mpeg",
                fault.equals("missing")?null:fault.equals("sha")?"b".repeat(64):expected.sha256());
        assertThrows(AudioStreamInconsistentException.class,()->storage.open(expected,new AudioByteRange(2,4)));
        verify(response).abort();
    }
    @Test
    void should_reject_transformed_content_before_exposing_stream() {
        var b=GetObjectResponse.builder().contentLength(10L).contentType("audio/mpeg").contentEncoding("gzip").metadata(Map.of("sha256",expected.sha256()));
        b.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());when(response.response()).thenReturn(b.build());
        assertThrows(AudioStreamInconsistentException.class,()->storage.open(expected,null));verify(response).abort();
    }
    @Test
    void should_map_upstream_404_416_and_connection_failure() {
        doThrow(S3Exception.builder().statusCode(404).message("secret").build()).when(client).getObject(any(GetObjectRequest.class));
        assertThrows(AudioObjectNotFoundException.class,()->storage.open(expected,null));
        doThrow(S3Exception.builder().statusCode(416).build()).when(client).getObject(any(GetObjectRequest.class));
        assertThrows(AudioStreamInconsistentException.class,()->storage.open(expected,new AudioByteRange(1,2)));
        doThrow(SdkClientException.create("private/secret")).when(client).getObject(any(GetObjectRequest.class));
        var failure=assertThrows(AudioStorageUnavailableException.class,()->storage.open(expected,null));assertNull(failure.getCause());
    }
}
