package org.example.music.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import org.example.music.user.dto.PasswordResetVerificationRequest;
import org.example.music.user.domain.PasswordResetIdentifiers;
import jakarta.validation.Validator;
import java.util.HashMap;
import org.example.music.user.exception.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

@Component
public class PasswordResetVerificationRequestReader {
    private static final int MAX_BYTES = 16384;
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(MAX_BYTES).maxNameLength(120).maxNestingDepth(1).build()).build();
    private final Validator validator;
    public PasswordResetVerificationRequestReader(Validator validator) { this.validator = validator; }

    public PasswordResetVerificationRequest read(HttpServletRequest request) {
        requireMediaType(request.getContentType());
        try {
            // 多读一个字节检测超限，不信任Content-Length；尾随空白同样消耗预算。
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new PasswordResetConfirmationValidationException();
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new PasswordResetConfirmationValidationException();
                var fields = new HashMap<String,String>();
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    if (!java.util.Set.of("challengeId", "code").contains(name)
                            || fields.containsKey(name) || parser.nextToken() != JsonToken.VALUE_STRING) {
                        throw new PasswordResetConfirmationValidationException();
                    }
                    fields.put(name, parser.getString());
                }
                if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null || fields.size() != 2) {
                    throw new PasswordResetConfirmationValidationException();
                }
                var result = new PasswordResetVerificationRequest(fields.get("challengeId"), fields.get("code"));
                if (!validator.validate(result).isEmpty() || !PasswordResetIdentifiers.isCanonicalDigest(result.challengeId())) {
                    throw new PasswordResetConfirmationValidationException();
                }
                return result;
            }
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw new PasswordResetConfirmationValidationException();
        } catch (PasswordResetConfirmationValidationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Password reset request binding failed unexpectedly", PasswordResetDiagnostics.sanitized(failure));
        }
    }

    private static void requireMediaType(String value) {
        try {
            if (value == null) throw new PasswordResetConfirmationUnsupportedMediaTypeException();
            var type = MediaType.parseMediaType(value);
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw new PasswordResetConfirmationUnsupportedMediaTypeException();
            }
        } catch (IllegalArgumentException failure) { throw new PasswordResetConfirmationUnsupportedMediaTypeException(); }
    }

}
