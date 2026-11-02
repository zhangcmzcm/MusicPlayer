package org.example.music.user.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class ChangePasswordRequestDeserializer extends ValueDeserializer<ChangePasswordRequest> {
    @Override
    public ChangePasswordRequest deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return invalid(context);
        }
        String oldPassword = null;
        String newPassword = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            if (parser.nextToken() != JsonToken.VALUE_STRING) {
                return invalid(context);
            }
            if ("oldPassword".equals(name) && oldPassword == null) {
                oldPassword = parser.getString();
            } else if ("newPassword".equals(name) && newPassword == null) {
                newPassword = parser.getString();
            } else {
                return invalid(context);
            }
        }
        if (parser.currentToken() != JsonToken.END_OBJECT || oldPassword == null || newPassword == null
                || parser.nextToken() != null) {
            return invalid(context);
        }
        return new ChangePasswordRequest(oldPassword, newPassword);
    }

    @Override
    public ChangePasswordRequest getNullValue(DeserializationContext context) {
        return invalid(context);
    }

    private ChangePasswordRequest invalid(DeserializationContext context) {
        return context.reportInputMismatch(ChangePasswordRequest.class,
                "Request must contain exactly oldPassword and newPassword string fields");
    }
}
