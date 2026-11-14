package com.stepwise.guide;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The object ids the loaded guide actually asks about, as a sorted primitive array.
 *
 * <p>An {@code objectPresent} sighting is recorded in the player's profile config and never
 * taken back. Object spawns fire in their thousands on every region load, so recording each
 * one would grow without bound. The watchlist limits the record to ids the guide names.
 *
 * <p>A sorted {@code int[]} rather than a {@code Set<Integer>} because the lookup runs in the
 * spawn handler. A boxed set allocates an {@code Integer} per spawn (object ids are above the
 * boxing cache), and a binary search allocates nothing.
 */
public final class WatchedObjectIds {
    private final int[] sortedIds;

    private WatchedObjectIds(int[] sortedIds) {
        this.sortedIds = sortedIds;
    }

    /** Whether the guide asks about this object id. Allocation-free; safe in a hot path. */
    public boolean contains(int objectId) {
        return Arrays.binarySearch(sortedIds, objectId) >= 0;
    }

    /** How many distinct ids the guide asks about. */
    public int size() {
        return sortedIds.length;
    }

    /** Collect the object ids named by every condition in the guide. */
    public static WatchedObjectIds fromGuide(StepwiseGuide guide) {
        Set<Integer> ids = new HashSet<>();
        for (CompletionCondition condition : guide.leafConditions()) {
            if (condition instanceof CompletionCondition.ObjectPresent) {
                ids.addAll(((CompletionCondition.ObjectPresent) condition).getObjectIds());
            }
        }
        int[] sorted = ids.stream().mapToInt(Integer::intValue).sorted().toArray();
        return new WatchedObjectIds(sorted);
    }
}
