package org.example.music.user.controller;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import org.example.music.user.dto.EmailChangeRequest;
import org.example.music.user.exception.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

@Component
public class EmailChangeRequestReader {
    private static final int MAX_BYTES = 16384;
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(MAX_BYTES).maxNameLength(120).maxNestingDepth(1).build()).build();
    private final Validator validator;
    public EmailChangeRequestReader(Validator validator) { this.validator = validator; }

    public EmailChangeRequest read(HttpServletRequest request) {
        requireMediaType(request.getContentType());
        try {
            // 多读一个字节检测超限，不信任Content-Length；尾随空白同样消耗预算。
            byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new EmailChangeValidationException();
            String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (var parser = JSON.createParser(body)) {
                if (parser.nextToken() != JsonToken.START_OBJECT || parser.nextToken() != JsonToken.PROPERTY_NAME
                        || !"newEmail".equals(parser.currentName()) || parser.nextToken() != JsonToken.VALUE_STRING) {
                    throw new EmailChangeValidationException();
                }
                String raw = parser.getString();
                // JSON转义可能产生孤立UTF-16代理字符；邮箱校验库并不拒绝，UTF-8编码却会替换它。
                if (raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0
                        || raw.codePoints().anyMatch(point -> point >= 0xd800 && point <= 0xdfff)) {
                    throw new EmailChangeValidationException();
                }
                var result = new EmailChangeRequest(raw.trim().toLowerCase(Locale.ROOT));
                if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null
                        || !validator.validate(result).isEmpty()) throw new EmailChangeValidationException();
                var address = new InternetAddress(result.newEmail(), true);
                address.validate();
                if (address.getPersonal() != null || address.isGroup() || !result.newEmail().equals(address.getAddress())) {
                    throw new EmailChangeValidationException();
                }
                return result;
            }
        } catch (IOException | AddressException | tools.jackson.core.JacksonException failure) {
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
