package org.example.music.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.example.music.user.dto.EmailChangeConfirmationRequest;
import org.example.music.user.exception.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

@Component
public class EmailChangeConfirmationRequestReader {
    private static final int MAX_BYTES = 16384;
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(MAX_BYTES).maxNameLength(120).maxNestingDepth(1).build()).build();
    private final Validator validator;
    public EmailChangeConfirmationRequestReader(Validator validator) { this.validator = validator; }

    public EmailChangeConfirmationRequest read(HttpServletRequest request) {
        requireMediaType(request.getContentType());
        try {
            // 多读一个字节检测超限，不信任Content-Length；尾随空白同样消耗预算。
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new EmailChangeValidationException();
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new EmailChangeValidationException();
                Map<String,String> fields = new HashMap<>();
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    if (!Set.of("challengeId", "code").contains(name) || fields.containsKey(name)
                            || parser.nextToken() != JsonToken.VALUE_STRING) throw new EmailChangeValidationException();
                    fields.put(name, parser.getString());
                }
                if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null
                        || !fields.keySet().equals(Set.of("challengeId", "code"))) throw new EmailChangeValidationException();
                var result = new EmailChangeConfirmationRequest(fields.get("challengeId"), fields.get("code"));
                if (!validator.validate(result).isEmpty()) throw new EmailChangeValidationException();
                byte[] decoded = Base64.getUrlDecoder().decode(result.challengeId());
                if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(result.challengeId())) {
                    throw new EmailChangeValidationException();
                }
                return result;
            }
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException failure) {
            throw new EmailChangeValidationException();
        } catch (EmailChangeValidationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Email change request binding failed unexpectedly", safe(failure));
        }
    }

    private static void requireMediaType(String value) {
        try {
            if (value == null) throw new EmailChangeUnsupportedMediaTypeException();
            var type = MediaType.parseMediaType(value);
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw new EmailChangeUnsupportedMediaTypeException();
            }
        } catch (IllegalArgumentException failure) { throw new EmailChangeUnsupportedMediaTypeException(); }
    }

    private static Throwable safe(Throwable failure) {
        var safe = new RuntimeException("Email change request binding failure");
        safe.setStackTrace(failure.getStackTrace()); return safe;
    }
}
