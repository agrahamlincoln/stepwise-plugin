package com.stepwise.guide;

import lombok.Value;

/**
 * A {@link StepAssumption} resolved to guide positions at load: in force while
 * {@code fromPosition <= position < untilPosition}.
 */
@Value
public class AssumptionSpan {
    int fromPosition;
    /** Exclusive; {@link Integer#MAX_VALUE} when the assumption runs to the end of the guide. */
    int untilPosition;
    StepAssumption assumption;

    public boolean inForceAt(int position) {
        return position >= fromPosition && position < untilPosition;
    }
}
