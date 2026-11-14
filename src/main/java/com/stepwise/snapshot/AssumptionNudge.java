package com.stepwise.snapshot;

import lombok.Value;

/** One route assumption the player is not meeting, as the restock chip and the overlay draw it. */
@Value
public class AssumptionNudge {
    /** Short name of what ran out, e.g. "Digsite pendants": the chip's text. */
    String label;
    /** The item the chip's icon shows. */
    int itemId;
    /** The reminder: what ran out and how to get more. */
    String reminder;
}
