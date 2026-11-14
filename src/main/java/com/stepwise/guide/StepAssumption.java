package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Value;

/**
 * Something the route expects the player to keep on them, from the step that carries it
 * (inclusive, by guide position) until {@link #untilStep} (exclusive) or the end of the guide.
 * The plugin reminds the player when {@link #check} reads unmet: for an {@code inventory}
 * check only while the bank is open, where the fix is one click away, and for an
 * {@code anywhere} check from the first bank sighting until the player restocks.
 *
 * <p>{@link #check} is a {@code hasItem} or {@code hasItemTotal} condition. A {@code hasItem}
 * at location {@code inventory} counts worn items, so it reads "worn or carried". It never
 * takes part in serves edges or auto-latch: it describes a standing loadout, not an item one
 * step produces for another.
 */
@Value
public class StepAssumption {
    /** Short imperative shown as the reminder, e.g. "Bring a ring of dueling". */
    @SerializedName("reminder") String reminder;
    @SerializedName("check") CompletionCondition check;
    /** First step at which the assumption no longer holds; null runs to the end of the guide. */
    @SerializedName("untilStep") StepId untilStep;
}
