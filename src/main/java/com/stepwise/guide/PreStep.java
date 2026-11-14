package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Value;

/**
 * A navigation or preparation step shown before an action step. The {@code preSteps} field
 * of {@link GuideStep} documents how pre-steps complete and what they do not affect.
 */
@Value
public class PreStep {
    /** Human-readable navigation instruction (e.g. "Minigame teleport to Castle Wars"). */
    @SerializedName("instruction") String instruction;
    /** How to detect that the player has arrived or prepared. */
    @SerializedName("completion") CompletionCondition completion;
}
