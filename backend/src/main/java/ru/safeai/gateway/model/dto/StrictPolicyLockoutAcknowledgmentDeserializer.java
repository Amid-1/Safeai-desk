package ru.safeai.gateway.model.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Осознанное согласие может быть выражено только JSON-литералом true. */
public final class StrictPolicyLockoutAcknowledgmentDeserializer extends ValueDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (parser.currentToken() == JsonToken.VALUE_FALSE) {
            return false;
        }
        return context.reportInputMismatch(Boolean.class, "Lockout acknowledgment must be a JSON boolean");
    }
}
