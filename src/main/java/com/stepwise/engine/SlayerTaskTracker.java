package com.stepwise.engine;

import com.stepwise.guide.SlayerMasters;

/**
 * Watches the Slayer task vars and reports the moment a task is killed out.
 *
 * <p>The game keeps no counter of finished tasks. {@code SLAYER_COUNT} (creatures left on the
 * current task) reads zero both for a player who just finished a task and for one who never
 * had one, so a finished task is knowable only as an event seen as it happens. The results
 * accumulate in {@code PlayerState.slayerTasksCompleted}. For a player this class never saw,
 * {@code CompletionCondition.NoSlayerTask} infers completion from the guide position instead.
 *
 * <h2>Ending a task is not finishing it</h2>
 *
 * All three ways a task can end zero {@code SLAYER_COUNT}. The task-streak varbit tells them
 * apart:
 *
 * <ul>
 *   <li><b>Killed out</b>: streak goes <b>up</b>. Only this one counts.</li>
 *   <li><b>Cancelled for 30 points</b>: streak is <b>unchanged</b>.</li>
 *   <li><b>Reset at Turael</b>: streak drops to <b>0</b>.</li>
 * </ul>
 *
 * Requiring a strictly increasing streak rejects both cancel paths in the safe direction. A
 * cancelled task never latches the step. A completion the tracker misses leaves the step open
 * for the player to mark done.
 *
 * <h2>Polled per tick, not driven by var events</h2>
 *
 * The decision needs {@code SLAYER_COUNT} and the streak read together, and var-change events
 * arrive one var at a time with no ordering guarantee within a tick. RuneLite's Slayer plugin
 * avoids the same problem by deferring {@code updateTask} to {@code clientThread.invokeLater}.
 *
 * <p>Not thread safe: both callers (the game-tick poller and the logout reset) run on the
 * client thread.
 *
 * <p>Deals only in the raw master value. The master names a guide condition uses map to values in
 * {@link SlayerMasters}.
 */
public final class SlayerTaskTracker {

    /** VarPlayer holding creatures remaining on the current task; 0 when there is none. */
    public static final int VARP_SLAYER_COUNT = 394;
    /** Varbit naming which master assigned the current task. */
    public static final int VARBIT_SLAYER_MASTER = 4067;
    /** Consecutive completed tasks. Krystilia keeps her own; see {@link #VARBIT_WILDERNESS_STREAK}. */
    public static final int VARBIT_TASK_STREAK = 4069;
    /** Krystilia's separate streak counter — Wilderness tasks do not touch the main one. */
    public static final int VARBIT_WILDERNESS_STREAK = 5617;

    /**
     * False until the first sample. The first reading is a baseline, never a transition, so
     * logging in mid-task does not compare against an implicit zero.
     */
    private boolean seeded;
    private int lastCount;
    private int lastStreak;
    private int lastWildernessStreak;

    /**
     * Fold one tick's sample in, returning the master whose task was just killed out, or
     * null on every other tick.
     *
     * @param count            {@link #VARP_SLAYER_COUNT}
     * @param master           {@link #VARBIT_SLAYER_MASTER}
     * @param streak           {@link #VARBIT_TASK_STREAK}
     * @param wildernessStreak {@link #VARBIT_WILDERNESS_STREAK}
     */
    public Integer observe(int count, int master, int streak, int wildernessStreak) {
        if (!seeded) {
            seeded = true;
            lastCount = count;
            lastStreak = streak;
            lastWildernessStreak = wildernessStreak;
            return null;
        }

        int previousCount = lastCount;
        int previousStreak = lastStreak;
        int previousWildernessStreak = lastWildernessStreak;
        lastCount = count;
        lastStreak = streak;
        lastWildernessStreak = wildernessStreak;

        // A task ends only when the remaining count reaches zero from above.
        if (previousCount <= 0 || count != 0) {
            return null;
        }

        // A Krystilia task advances her own streak and leaves the main one alone.
        boolean wilderness = master == SlayerMasters.KRYSTILIA;
        int before = wilderness ? previousWildernessStreak : previousStreak;
        int after = wilderness ? wildernessStreak : streak;
        if (after <= before) {
            return null;
        }

        return master;
    }

    /**
     * Drop the baseline so the next sample re-establishes it. Called on logout, so one
     * account's count never compares against another's.
     */
    public void reset() {
        seeded = false;
        lastCount = 0;
        lastStreak = 0;
        lastWildernessStreak = 0;
    }
}
