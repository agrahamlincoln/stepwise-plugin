package com.stepwise.guide;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.lang.reflect.Type;

/**
 * Gate on game state that must hold before a background step is shown. It has no
 * step-position kind because the step's own place in the guide covers position, and no
 * free-text kind because a gate the runtime cannot evaluate would never open. A kind
 * the loader does not recognise loads as {@link Unknown}. Evaluated by
 * {@link com.stepwise.engine.ActivationGate#isOpen}.
 */
public abstract class StepActivation {
    public abstract String getType();

    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class SkillLevel extends StepActivation {
        @SerializedName("skill") String skill;
        @SerializedName("level") int level;

        @Override
        public String getType() {
            return "skillLevel";
        }
    }

    /** Open once the player's total level reaches {@link #level}. */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class TotalLevel extends StepActivation {
        @SerializedName("level") int level;

        @Override
        public String getType() {
            return "totalLevel";
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class QuestComplete extends StepActivation {
        @SerializedName("quest_name") String questName;

        @Override
        public String getType() {
            return "questComplete";
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class HasItem extends StepActivation {
        @SerializedName("item_name") String itemName;

        @Override
        public String getType() {
            return "hasItem";
        }
    }

    /**
     * A gate whose {@code type} the plugin does not recognise. It never opens, so the gated
     * row stays hidden, but the rest of the guide still loads.
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    public static class Unknown extends StepActivation {
        String type;
    }

    public static class TypeAdapter implements JsonDeserializer<StepActivation> {
        @Override
        public StepActivation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            JsonElement typeElement = obj.get("type");
            if (typeElement == null || typeElement.isJsonNull()) {
                return new Unknown("(missing)");
            }
            String type = typeElement.getAsString();
            switch (type) {
                case "skillLevel":
                    return context.deserialize(json, SkillLevel.class);
                case "totalLevel":
                    return context.deserialize(json, TotalLevel.class);
                case "questComplete":
                    return context.deserialize(json, QuestComplete.class);
                case "hasItem":
                    return context.deserialize(json, HasItem.class);
                default:
                    return new Unknown(type);
            }
        }
    }
}
