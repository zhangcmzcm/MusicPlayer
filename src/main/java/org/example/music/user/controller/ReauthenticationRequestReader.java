package org.example.music.user.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.example.music.user.domain.ReauthenticationMethod;
import org.example.music.user.dto.ReauthenticationRequest;
import org.example.music.user.exception.ReauthenticationValidationException;
import org.example.music.user.exception.ReauthenticationDiagnostics;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

@Component
public class ReauthenticationRequestReader {
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(128).maxNameLength(128).maxNestingDepth(1).build()).build();
    private static final Set<String> FIELDS = Set.of("method", "password", "challengeId", "code");
    private final Validator validator;

    public ReauthenticationRequestReader(Validator validator) { this.validator = validator; }

    public ReauthenticationRequest read(HttpServletRequest request) {
        try {
            String contentType = request.getContentType();
            if (contentType == null) throw new ReauthenticationValidationException();
            MediaType type = MediaType.parseMediaType(contentType);
            if (!type.getType().equalsIgnoreCase("application") || !type.getSubtype().equalsIgnoreCase("json")
                    || (type.getCharset() != null && !type.getCharset().equals(StandardCharsets.UTF_8))) {
                throw new ReauthenticationValidationException();
            }
            // Reader禁用编码自动探测，非法UTF-8不能被替换后当成另一份密码。
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var parser = JSON.createParser(new InputStreamReader(request.getInputStream(), decoder))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw new ReauthenticationValidationException();
                Map<String, String> fields = new HashMap<>();
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String name = parser.currentName();
                    if (!FIELDS.contains(name) || fields.containsKey(name) || parser.nextToken() != JsonToken.VALUE_STRING) {
                        throw new ReauthenticationValidationException();
                    }
                    String value = parser.getString();
                    if (value.length() > 128) throw new ReauthenticationValidationException();
                    fields.put(name, value);
                }
                if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) throw new ReauthenticationValidationException();
                ReauthenticationMethod method = switch (fields.getOrDefault("method", "")) {
                    case "PASSWORD" -> ReauthenticationMethod.PASSWORD;
                    case "EMAIL_CODE" -> ReauthenticationMethod.EMAIL_CODE;
                    default -> throw new ReauthenticationValidationException();
                };
                Set<String> required = method == ReauthenticationMethod.PASSWORD ? Set.of("method", "password") : Set.of("method", "challengeId", "code");
                if (!fields.keySet().equals(required)) throw new ReauthenticationValidationException();
                var result = new ReauthenticationRequest(method, fields.get("password"), fields.get("challengeId"), fields.get("code"));
                if (!validator.validate(result).isEmpty()) throw new ReauthenticationValidationException();
                return result;
            }
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException failure) {
            throw new ReauthenticationValidationException();
        } catch (ReauthenticationValidationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Reauthentication request binding failed unexpectedly", ReauthenticationDiagnostics.sanitized(failure));
        }
    }
}
