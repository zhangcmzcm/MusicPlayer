package org.example.music.track.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.example.music.storage.domain.AudioByteRange;
import org.example.music.storage.domain.AudioStreamStorage;
import org.example.music.storage.domain.VerifiedAudioStream;
import org.example.music.storage.exception.AudioObjectNotFoundException;
import org.example.music.storage.exception.AudioStorageUnavailableException;
import org.example.music.storage.exception.AudioStreamInconsistentException;
import org.example.music.track.exception.TrackAudioStorageInconsistentException;
import org.example.music.track.service.PreparedTrackStream;
import org.example.music.track.service.TrackStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TrackStreamResponseWriter {
    private static final Logger LOGGER=LoggerFactory.getLogger(TrackStreamResponseWriter.class);
    private static final Set<String> CONTENT_HEADERS=Set.of("content-length","content-type","content-range","etag","last-modified","accept-ranges","cache-control");
    private final AudioStreamStorage storage;
    public TrackStreamResponseWriter(AudioStreamStorage storage) { this.storage=storage; }

    public static void cacheHeaders(PreparedTrackStream prepared,HttpServletResponse response) {
        response.setHeader("ETag",prepared.etag());
        response.setDateHeader("Last-Modified",prepared.lastModified().toEpochMilli());
        response.setHeader("Cache-Control","private, no-cache");
        var vary=new LinkedHashSet<String>();
        for(String value:response.getHeaders("Vary")) {
            Arrays.stream(value.split(",")).map(String::strip).filter(v->!v.isEmpty()).forEach(vary::add);
        }
        if(vary.stream().noneMatch(v->v.equalsIgnoreCase("Authorization"))) { vary.add("Authorization"); }
        response.setHeader("Vary",String.join(", ",vary));
    }

    public static void contentHeaders(PreparedTrackStream prepared,AudioByteRange range,HttpServletResponse response) {
        var meta=prepared.snapshot().metadata();
        response.setStatus(range==null?200:206);
        response.setContentType(meta.contentType());
        response.setContentLengthLong(range==null?meta.sizeBytes():range.length());
        response.setHeader("Accept-Ranges","bytes");
        if(range!=null) { response.setHeader("Content-Range","bytes "+range.start()+"-"+range.end()+"/"+meta.sizeBytes()); }
        cacheHeaders(prepared,response);
    }

    private VerifiedAudioStream open(PreparedTrackStream prepared,AudioByteRange range) {
        try { return storage.open(prepared.snapshot().metadata(),range); }
        catch(AudioObjectNotFoundException exception) { throw TrackStreamService.missing(prepared.snapshot().trackId()); }
        catch(AudioStreamInconsistentException exception) { throw new TrackAudioStorageInconsistentException(); }
    }

    public void write(PreparedTrackStream prepared,AudioByteRange range,HttpServletResponse response) throws IOException {
        try(var source=open(prepared,range)) {
            long expected=range==null?prepared.snapshot().metadata().sizeBytes():range.length();
            if(source.contentLength()!=expected) { throw new TrackAudioStorageInconsistentException(); }
            var input=source.content();
            byte[] buffer=new byte[8192];
            long remaining=expected;
            int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
            if(count<0) { throw new EOFException(); }
            contentHeaders(prepared,range,response);
            var output=response.getOutputStream();
            while(remaining>0) {
                if(count<0) { throw new EOFException(); }
                if(count>0) { output.write(buffer,0,count);remaining-=count; }
                if(remaining>0) { count=input.read(buffer,0,(int)Math.min(buffer.length,remaining)); }
            }
            source.completed();
        } catch(IOException exception) {
            interrupted(prepared,response);
            if(response.isCommitted()) { throw new IOException("Audio transfer interrupted"); }
            clearContentHeaders(response);
            throw new AudioStorageUnavailableException("Audio transfer could not be completed",null);
        } catch(RuntimeException exception) {
            if(response.isCommitted()) {
                interrupted(prepared,response);
                throw new IOException("Audio transfer interrupted");
            }
            clearContentHeaders(response);
            throw exception;
        }
    }

    private void interrupted(PreparedTrackStream prepared,HttpServletResponse response) {
        LOGGER.warn("Audio transfer interrupted, trackId={}, traceId={}, committed={}",prepared.snapshot().trackId(),MDC.get("traceId"),response.isCommitted());
    }

    private void clearContentHeaders(HttpServletResponse response) {
        var retained=new LinkedHashMap<String,List<String>>();
        for(String name:response.getHeaderNames()) {
            if(!CONTENT_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) { retained.put(name,List.copyOf(response.getHeaders(name))); }
        }
        response.reset();
        retained.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));
        response.setHeader("Cache-Control","no-store");
    }
}
