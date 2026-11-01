package org.example.music.user.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class UpdateCurrentUserRequestDeserializer extends ValueDeserializer<UpdateCurrentUserRequest> {
    @Override
    public UpdateCurrentUserRequest deserialize(JsonParser parser, DeserializationContext context) {
        // 按 token 顺序读取，避免树模型先覆盖重复属性，也不改变全局 Jackson 行为。
        if (parser.currentToken() != JsonToken.START_OBJECT
                || parser.nextToken() != JsonToken.PROPERTY_NAME
                || !"username".equals(parser.currentName())
                || parser.nextToken() != JsonToken.VALUE_STRING) {
            return invalid(context);
        }
        String username = parser.getString();
        if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
            return invalid(context);
        }
        return new UpdateCurrentUserRequest(username);
    }

    @Override
    public UpdateCurrentUserRequest getNullValue(DeserializationContext context) {
        return invalid(context);
    }

    private UpdateCurrentUserRequest invalid(DeserializationContext context) {
        return context.reportInputMismatch(UpdateCurrentUserRequest.class,
                "Request must contain exactly one string username field");
    }
}
