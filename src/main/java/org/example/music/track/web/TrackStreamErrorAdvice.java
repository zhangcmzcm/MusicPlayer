package org.example.music.track.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class TrackStreamErrorAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter returnType,Class<? extends HttpMessageConverter<?>> converterType) { return true; }
    @Override public Object beforeBodyWrite(Object body,MethodParameter returnType,MediaType media,
            Class<? extends HttpMessageConverter<?>> converterType,ServerHttpRequest request,ServerHttpResponse response) {
        if(body instanceof ProblemDetail problem && request.getURI().getPath().matches("/api/v1/tracks/[^/]+/stream")) {
            var headers=response.getHeaders();
            headers.setCacheControl("no-store");
            headers.remove("ETag"); headers.remove("Last-Modified"); headers.remove("Accept-Ranges"); headers.remove("Content-Length");
            if(problem.getStatus()!=416) { headers.remove("Content-Range"); }
        }
        return body;
    }
}
