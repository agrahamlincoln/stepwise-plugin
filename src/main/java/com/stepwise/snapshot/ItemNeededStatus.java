package com.stepwise.snapshot;

import lombok.Value;

/**
 * Snapshot-time ownership of one row of the active entry's items-needed list.
 *
 * <p>Computed once per snapshot in {@code SnapshotBuilder} from the resolved
 * {@code com.stepwise.guide.ItemNeeded} row and the player's current containers.
 * {@code ItemsNeededView} only reads it, never recomputes it.
 *
 * <p>{@link Ownership#UNKNOWN} must never render as {@link Ownership#MISSING}: on a fresh
 * install no bank has been seen yet, and greying out the whole grid would claim something
 * the plugin has no evidence for.
 */
@Value
public class ItemNeededStatus {

    /** How settled this row's ownership is; the square's border shows it. */
    public enum Ownership {
        /** In inventory or worn: nothing to do, this one is ready. */
        CARRIED,
        /** Owned, but in the bank or a side container the player must travel to. */
        BANKED,
        /** The bank has been seen, and the item is nowhere. */
        MISSING,
        /**
         * The bank has never been seen, so nothing beyond "not carried" can
         * honestly be said. Must never render as {@link #MISSING} — see the class doc.
         */
        UNKNOWN
    }

    /** The guide's row, verbatim — the tooltip, and all a text-only row ever shows. */
    String text;
    /** The sprite to draw, or 0 when the row names no single item. */
    int itemId;
    /** What the row asks for, or 0 when the source wrote no number — not the same as one. */
    int quantity;
    Ownership ownership;
}
