package com.stepwise.snapshot;

import lombok.Value;
import lombok.Builder;

@Value
@Builder
public class CriterionProgress {
    int current;
    int required;
    /** Items available but not yet in the required location (e.g., inventory items for a bank target). */
    int pending;
    String bankNudge;

    /** Populated only for skillLevel criteria. */
    int currentLevel;
    int targetLevel;

    /**
     * XP thresholds for each level from currentLevel+1 to targetLevel-1, drawn as markers
     * on the progress bar. Null for non-skill bars.
     */
    int[] levelMilestoneXp;
}
