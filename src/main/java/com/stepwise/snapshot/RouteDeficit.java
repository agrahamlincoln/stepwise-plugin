package com.stepwise.snapshot;

import lombok.AllArgsConstructor;
import lombok.Value;

import java.util.List;

/**
 * Where the player stands against the route's plan at the current entry.
 *
 * <p>Purely advisory: nothing here completes, latches, or fast-forwards a step. It shows a
 * player who has diverged from the route in either direction; a GP surplus makes skipping an
 * earn phase a real option.
 *
 * <p>All comparisons are against the entry's <em>opening</em> expectation, the balance and
 * levels the route assumes on arrival. The closing expectation would report a deficit for
 * every player partway through the entry.
 */
@Value
@AllArgsConstructor
public class RouteDeficit {

    /** The entry being compared against — the one holding the active step. */
    String entryId;

    /**
     * Whether the player's coin total is knowable yet.
     *
     * <p>Bank contents are only known once the player opens a bank, so before that their coins
     * read as zero. Every GP-derived figure below is meaningless until this is true, and the
     * panel suppresses those rows.
     */
    boolean gpKnown;

    /** Coins the player holds across inventory and bank. Meaningless unless {@link #gpKnown}. */
    long playerGp;

    /** Balance the route expects on arriving at this entry. */
    long routeGp;

    /** Every skill the route profiles at this entry, on-route ones included, in no set order. */
    List<SkillDelta> skills;

    /** Present when this entry opens an earn phase that could be skipped; otherwise null. */
    SkipAdvice skipAdvice;

    /**
     * Quests the guide finishes before the active step that the player has not completed, in
     * guide order. Empty when every one is done or the quest list has not been read yet.
     */
    List<QuestBehind> questsBehind;

    /** Quests the player has finished that the guide finishes at or after the active step. */
    int questsAhead;

    /**
     * Whether the quest list has been read. Until then no quest reads as finished or not, and
     * the quest comparison says nothing.
     */
    boolean questsKnown;

    /** A deficit without the quest comparison, whose quest list is unread. */
    public RouteDeficit(String entryId, boolean gpKnown, long playerGp, long routeGp,
                        List<SkillDelta> skills, SkipAdvice skipAdvice) {
        this(entryId, gpKnown, playerGp, routeGp, skills, skipAdvice, List.of(), 0, false);
    }

    /** A quest the guide has already finished by the active step, and the player has not. */
    @Value
    public static class QuestBehind {
        /** As RuneLite's quest list names it. */
        String questName;
        /** The first step that finishes it. */
        String stepId;
        /** Whether the player has started it. */
        boolean started;
    }

    /** Base level held versus base level the route assumes, for one skill. */
    @Value
    public static class SkillDelta {
        String skill;
        /** Base level, never boosted — the same basis the guide's skill latches use. */
        int playerLevel;
        int routeLevel;

        /** Negative when behind the route, positive when ahead. */
        public int getDelta() {
            return playerLevel - routeLevel;
        }
    }

    /**
     * What skipping the earn phase starting at this entry would cost and require.
     *
     * <p>Scoped to the whole <em>earn arc</em>, the run of consecutive earning entries starting
     * here, because the route sizes an earn phase as one economic unit.
     */
    @Value
    public static class SkipAdvice {
        /**
         * Every entry in the earn arc, in guide order, starting with the current one. The skip
         * action operates on these.
         */
        List<String> arcEntryIds;

        /**
         * Coins needed on hand to fund the route from here until it next earns its way back into
         * the black: the deepest point of the trough that follows, not the arc's closing balance.
         */
        long horizonGp;

        /** True when the player already holds {@link #horizonGp}. Advisory only. */
        boolean affordable;

        /** XP the arc would have earned, by skill: the real price of skipping it. */
        List<XpAward> xpCarried;
    }

    /** XP in one skill, largest first in {@link SkipAdvice#getXpCarried()}. */
    @Value
    public static class XpAward {
        String skill;
        long xp;
    }
}
