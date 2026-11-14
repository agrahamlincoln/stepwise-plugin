package com.stepwise.guide;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Where the player stands in the guide for advice that follows the route: the active step.
 * Completed steps further on do not move it, because skill-level and stockpile steps latch
 * whenever the account meets them, often long before the route reaches them. The furthest
 * completed step stands in only when no step is active, as once the guide is finished.
 */
public final class RoutePosition {
    /** No position: no active step in the guide, and no step completed. */
    public static final int NONE = -1;

    private RoutePosition() {
    }

    /**
     * @param positionIndex step id to guide order, from the loaded guide
     * @param activeStepId  the step the player is on, or null when none is active
     * @param completed     completed steps; ids the guide does not have are ignored
     */
    public static int of(Map<String, Integer> positionIndex, @Nullable String activeStepId,
                         Iterable<StepId> completed) {
        int active = activeStepId == null ? NONE : positionIndex.getOrDefault(activeStepId, NONE);
        return of(positionIndex, active, completed);
    }

    /**
     * As {@link #of(Map, String, Iterable)}, with the active step already placed;
     * {@link #NONE} when no step is active.
     */
    public static int of(Map<String, Integer> positionIndex, int activePosition,
                         Iterable<StepId> completed) {
        if (activePosition != NONE) {
            return activePosition;
        }
        int latest = NONE;
        for (StepId id : completed) {
            Integer at = positionIndex.get(id.getValue());
            if (at != null && at > latest) {
                latest = at;
            }
        }
        return latest;
    }
}
