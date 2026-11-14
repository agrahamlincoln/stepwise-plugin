package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Value;

/**
 * A single milestone — a named guide position with verifiable completion criteria.
 *
 * <p>The {@code anchorStepId} is the last step within this milestone's scope.
 * The next milestone's scope begins at the step immediately following this anchor.
 *
 * <p>The {@code criteria} is a composite {@link CompletionCondition} (always
 * {@code type=composite}) evaluated against current game state to determine whether
 * the player has cleared this milestone.
 */
@Value
public class Milestone {
    String id;
    String label;
    @SerializedName("anchor_step_id") String anchorStepId;
    CompletionCondition criteria;
}
