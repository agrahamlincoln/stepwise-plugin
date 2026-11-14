package com.stepwise.guide;

import com.google.gson.*;
import lombok.Value;

import java.lang.reflect.Type;

@Value
public class StepId {
    String value;

    @Override
    public String toString() {
        return value;
    }

    public static class TypeAdapter implements JsonSerializer<StepId>, JsonDeserializer<StepId> {
        @Override
        public StepId deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            return new StepId(json.getAsString());
        }

        @Override
        public JsonElement serialize(StepId src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getValue());
        }
    }
}
