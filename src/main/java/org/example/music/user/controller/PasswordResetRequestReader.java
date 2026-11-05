package org.example.music.user.controller;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import org.example.music.user.dto.PasswordResetRequest;
import org.example.music.user.exception.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

@Component
public class PasswordResetRequestReader {
    private static final int MAX_BYTES = 16384;
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(MAX_BYTES).maxNameLength(120).maxNestingDepth(1).build()).build();
    private final Validator validator;
    public PasswordResetRequestReader(Validator validator) { this.validator = validator; }

    public PasswordResetRequest read(HttpServletRequest request) {
        requireMediaType(request.getContentType());
        try {
            // 多读一个字节检测超限，不信任Content-Length；尾随空白同样消耗预算。
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new PasswordResetValidationException();
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT || parser.nextToken() != JsonToken.PROPERTY_NAME
                        || !"email".equals(parser.currentName()) || parser.nextToken() != JsonToken.VALUE_STRING) {
                    throw new PasswordResetValidationException();
                }
                String raw = parser.getString();
                // JSON转义可能产生孤立UTF-16代理字符；邮箱校验库并不拒绝，UTF-8编码却会替换它。
                if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0
                        || raw.codePoints().anyMatch(point -> point >= 0xd800 && point <= 0xdfff)) {
                    throw new PasswordResetValidationException();
                }
                var result = new PasswordResetRequest(raw.trim().toLowerCase(Locale.ROOT));
                if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null
                        || !validator.validate(result).isEmpty()) throw new PasswordResetValidationException();
                var address = new InternetAddress(result.email(), true);
                address.validate();
                if (address.getPersonal() != null || address.isGroup() || !result.email().equals(address.getAddress())) {
                    throw new PasswordResetValidationException();
                }
                return result;
            }
        } catch (IOException | AddressException | tools.jackson.core.JacksonException failure) {
            throw new PasswordResetValidationException();
        } catch (PasswordResetValidationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Password reset request binding failed unexpectedly", safe(failure));
        }
    }

    private static void requireMediaType(String value) {
        try {
            if (value == null) throw new PasswordResetUnsupportedMediaTypeException();
            var type = MediaType.parseMediaType(value);
            if (!"application".equalsIgnoreCase(type.getType()) || !"json".equalsIgnoreCase(type.getSubtype())
                    || (type.getCharset() != null && !StandardCharsets.UTF_8.equals(type.getCharset()))) {
                throw new PasswordResetUnsupportedMediaTypeException();
            }
        } catch (IllegalArgumentException failure) { throw new PasswordResetUnsupportedMediaTypeException(); }
    }

    private static Throwable safe(Throwable failure) {
        var safe = new RuntimeException("Password reset request binding failure");
        safe.setStackTrace(failure.getStackTrace()); return safe;
    }
}
