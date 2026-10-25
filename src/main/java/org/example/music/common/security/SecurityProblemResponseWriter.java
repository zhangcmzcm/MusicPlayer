package org.example.music.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

public class SecurityProblemResponseWriter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String title,
            String detail
    ) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "urn:problem:music:" + code.toLowerCase());
        problem.put("title", title);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("status", status.value());
        problem.put("code", code);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
