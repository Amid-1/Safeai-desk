package ru.safeai.gateway.model.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Rejects coercion of strings, booleans and fractional JSON numbers into token limits.
 * The nullable boxed Integer preserves the existing no-additional-limit contract.
 */
public final class StrictPolicyTokenLimitDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class, "Token limit must be a JSON integer");
        }
        return parser.getIntValue();
    }
}
