package com.stepwise.snapshot;

import lombok.Value;
import lombok.Builder;

@Value
@Builder
public class CompletionCriterion {
    String description;
    boolean met;

    /**
     * True when the resolution engine treats this condition as satisfied because the
     * downstream step it serves is already complete, whatever the game state says.
     */
    boolean waived;

    /**
     * Banked quantity backing the "withdraw it" hint. Non-zero only for an unmet
     * inventory-scoped item condition that a bank withdrawal would fully satisfy.
     */
    int inBankCount;

    /**
     * Icon type: "skill", "item", "gp", "quest", "diary", "kill", "combat_achievement" or
     * "location"; null when the condition has no icon.
     */
    String iconType;

    /** Icon key: skill name for skills, item ID for items, coin quantity for GP, null otherwise. */
    String iconKey;

    CriterionProgress progress;
}
