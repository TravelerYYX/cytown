package com.yyx.ciyuantown.business.appuser.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.io.IOException;

public record ExperienceAdjustmentParam(
        @NotNull @Positive Long id,
        @NotNull @JsonDeserialize(using = IntegerDeltaDeserializer.class) Long delta,
        String reason,
        String requestId) {
    /** Jackson normally truncates fractional JSON numbers when binding a Long. */
    public static class IntegerDeltaDeserializer extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            return parser.getLongValue();
        }
    }
}
