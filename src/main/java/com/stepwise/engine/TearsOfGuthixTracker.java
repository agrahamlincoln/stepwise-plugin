package com.stepwise.engine;

import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.gameval.VarbitID;

/**
 * When Juna will next let the player drink the tears.
 *
 * <p>No var carries the weekly wait. The server writes the Quest Journal's "in N days" text.
 *
 * <p>{@link VarbitID#TOG_COUNTDOWN} is not the wait, despite the name. The client script
 * {@code [proc,tog_sidepanel_timer]} uses it as the width of the in-chasm progress bar, and
 * it is zero everywhere outside the cave. Non-zero therefore means the player is spending
 * this week's visit, so it arms the clock the way {@link BirdhouseTracker} stamps a seeding.
 *
 * <p>Four sources feed the clock: the tick bar stamps a visit, Juna's login line (when her
 * reminder is on) marks it ready, the journal page states the server's own day count, and
 * Juna's dialogue states the wait. The second gate, 100,000 experience or a quest point since
 * the last visit, is armed from the totals at each cave entry, and by Juna's refusal when no
 * unmet baseline is held; see {@link #observeVisit} and {@link #noteAdventuresNeeded}. The
 * journal page states what is owed too, but only its day count is read.
 *
 * <p>Juna resets at the start of the seventh day after a visit, so the wait is measured
 * from UTC midnight.
 */
public class TearsOfGuthixTracker {

    /** The in-cave tick bar — non-zero only while the player is collecting tears. */
    public static final int IN_CAVE_TICKS_VARBIT = VarbitID.TOG_COUNTDOWN;

    /** Key this quest's completion is stored under in {@link PlayerState#getQuests()}. */
    public static final String QUEST_NAME = "Tears of Guthix";

    /** Juna resets at the start of the seventh day, so nothing longer is a valid wait. */
    public static final int WEEK_DAYS = 7;

    private static final long DAY_SECONDS = 86400L;

    private static final long WEEK_SECONDS = WEEK_DAYS * DAY_SECONDS;

    /** The eligibility line Juna offers once a day on login, matched on its middle. */
    private static final String ELIGIBLE_MESSAGE = "eligible to drink from the Tears of Guthix";

    /** No day count could be read from the journal page. See {@link #parseJournalDays}. */
    public static final int JOURNAL_DAYS_UNREAD = -1;

    /**
     * The wait as the journal states it. Only "in N days" is matched, so the words around the
     * count do not matter.
     */
    private static final Pattern JOURNAL_WAIT =
        Pattern.compile("\\bin\\s+(\\d{1,2})\\s+(?:more\\s+)?days?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Any day figure at all, for a page whose wording does not lead with "in". Used only
     * where the whole page states one such figure — see {@link #parseJournalDays}.
     */
    private static final Pattern JOURNAL_ANY_DAYS =
        Pattern.compile("\\b(\\d{1,2})\\s+(?:more\\s+)?days?\\b", Pattern.CASE_INSENSITIVE);

    /** The last day of the wait, which the page may word rather than number. */
    private static final Pattern JOURNAL_TOMORROW =
        Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE);

    /** Experience since the last visit that satisfies Juna's second gate. */
    public static final long ADVENTURE_XP = 100_000L;

    /** Quest points since the last visit that satisfy it instead. */
    public static final int ADVENTURE_QP = 1;

    /** How the speaker is named in a dialogue line, ahead of a {@code |}. */
    private static final String JUNA = "Juna";

    /** "Come back tomorrow" / "Come again in 3 days", the two shapes she states a wait in. */
    private static final Pattern JUNA_WAIT = Pattern.compile(
        "\\bcome\\s+(?:back|again)\\s+(?:(tomorrow)|in\\s+(\\d{1,2})\\s+days?)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * Her refusal when the week is up but the adventuring is not done. The line is reachable
     * only after the wait, so it sets the clock as well as the gate.
     */
    private static final Pattern JUNA_WAITED_BUT_IDLE = Pattern.compile(
        "\\bcome\\s+(?:back|again)\\s+when\\s+you\\s+have\\s+had\\s+more\\s+adventures\\b",
        Pattern.CASE_INSENSITIVE);

    /** Every way she asks for new adventures. Only ever matched against a line of hers. */
    private static final Pattern JUNA_WANTS_ADVENTURES =
        Pattern.compile("more\\s+adventures", Pattern.CASE_INSENSITIVE);

    /**
     * The message box that follows her refusals. It has no speaker, so it is the one line
     * accepted unattributed, which is safe only because no other box uses this wording.
     * A looser pattern would misfire: many NPCs say an unattributed "come back tomorrow".
     */
    private static final Pattern JUNA_GATE_BOX = Pattern.compile(
        "gained\\s+either\\s+one\\s+quest\\s+point", Pattern.CASE_INSENSITIVE);

    /**
     * Her "it has not been long since your last visit". She says this only when the
     * adventuring gate is met and the wait alone is left, so it clears the gate.
     */
    private static final Pattern JUNA_ONLY_THE_WAIT = Pattern.compile(
        "not\\s+been\\s+long\\s+since\\s+your\\s+last\\s+visit", Pattern.CASE_INSENSITIVE);

    /** Her "I will let you into the cave for a short time" — both halves of the gate pass. */
    private static final Pattern JUNA_LETTING_YOU_IN = Pattern.compile(
        "let\\s+you\\s+into\\s+the\\s+cave\\s+for\\s+a\\s+short\\s+time", Pattern.CASE_INSENSITIVE);

    /**
     * When Juna next resets, or 0 before any visit has been observed.
     *
     * <p>Volatile: the client thread writes it and the panel's EDT reads it.
     */
    private volatile long nextAvailableEpochSec;

    /**
     * The totals to measure new adventures against, or null while nothing says the gate is
     * unmet. One immutable object so the panel never sees experience and quest points from
     * different armings.
     *
     * <p>This field and the reset time are not published atomically together. A torn read
     * costs one cosmetic refresh, which the next event corrects.
     */
    private volatile Adventures adventures;

    /**
     * Whether the tick bar was running when last read, so a visit is stamped once. Not
     * persisted: a visit still running after a relog is stamped again, from that day and the
     * totals at that moment.
     */
    private volatile boolean visitInProgress;

    /** What the player's totals were when the gate was last known to be unmet. */
    private static final class Adventures {

        private final long xp;
        private final int questPoints;

        private Adventures(long xp, int questPoints) {
            this.xp = xp;
            this.questPoints = questPoints;
        }

        boolean metBy(long totalXp, int totalQuestPoints) {
            return totalQuestPoints - questPoints >= ADVENTURE_QP
                || totalXp - xp >= ADVENTURE_XP;
        }

        long xpToGo(long totalXp) {
            return Math.max(0L, Math.min(ADVENTURE_XP, xp + ADVENTURE_XP - totalXp));
        }
    }

    /** Whether the player has finished the quest, and so has a weekly visit at all. */
    public static boolean questCompleted(PlayerState state) {
        return state != null && state.getQuests().get(QUEST_NAME) == QuestStatus.COMPLETED;
    }

    /**
     * The clock as it stands at one instant, for a caller that asks more than one question.
     *
     * <p>The panel reads on the EDT while the client thread may stamp a visit. Reading the
     * tracker once per question could give two answers in one pass; one snapshot keeps the
     * pass consistent.
     */
    @Value
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Reading {

        /**
         * True when nothing has been observed yet. Distinct from ready: the panel sends the
         * player to their Quest Journal rather than to a minigame they may not be able to enter.
         */
        boolean unknown;

        /** Whether Juna has reset. False while unknown. */
        boolean ready;

        /** Whole days until Juna resets, rounded up. Zero when ready or unknown. */
        int daysRemaining;

        /**
         * Whether Juna wants new adventures before she will let the player in again.
         *
         * <p>False until a cave entry or her refusal has armed this gate, so false means met or
         * unknown, never a confirmed pass. With a ready clock this marks the week up but Juna
         * still refusing.
         */
        boolean adventuresNeeded;

        /**
         * Experience still owed on that gate, or 0 when it is not in the way. A quest point
         * also clears the gate, so this is a ceiling, not a requirement.
         */
        long xpToGo;

        /**
         * How much of the week has elapsed, 0.0 to 1.0. Zero while unknown, where the
         * caller is expected to have branched on {@link #isUnknown()} instead.
         */
        public double getProgress() {
            return unknown ? 0.0 : (double) (WEEK_DAYS - daysRemaining) / WEEK_DAYS;
        }
    }

    /**
     * Take a consistent reading of both gates: the week, and whether Juna is still owed
     * new adventures as of these totals. See {@link Reading}.
     *
     * @param totalXp     experience across all skills, or negative if not known
     * @param questPoints the player's quest points, or negative if not known
     */
    public Reading readAt(long nowEpochSec, long totalXp, int questPoints) {
        Adventures owed = adventures;
        boolean needed = owed != null && totalXp >= 0 && !owed.metBy(totalXp, questPoints);
        long xpToGo = needed ? owed.xpToGo(totalXp) : 0L;

        long next = nextAvailableEpochSec;
        if (next <= 0) {
            return new Reading(true, false, 0, needed, xpToGo);
        }
        if (nowEpochSec >= next) {
            return new Reading(false, true, 0, needed, xpToGo);
        }
        long days = (next - nowEpochSec + DAY_SECONDS - 1) / DAY_SECONDS;
        return new Reading(false, false, (int) Math.min(days, WEEK_DAYS), needed, xpToGo);
    }

    /**
     * Fold in a reading of the in-cave tick bar, and arm both halves of the next visit's
     * gate from the totals at the cave door.
     *
     * <p>Runs once per visit, keyed on the bar going from stopped to running. Keying on the
     * clock moving would misfire on a visit spanning UTC midnight: the wait would move a day
     * out and the gate would re-arm from post-tears totals.
     *
     * @return true if anything moved, so the caller should persist it
     */
    public boolean observeVisit(int ticksLeft, long nowEpochSec, long totalXp, int questPoints) {
        if (ticksLeft <= 0) {
            visitInProgress = false;
            return false;
        }
        if (visitInProgress) {
            return false;
        }
        visitInProgress = true;

        long next = startOfUtcDay(nowEpochSec) + WEEK_SECONDS;
        boolean moved = next != nextAvailableEpochSec;
        nextAvailableEpochSec = next;
        return armAdventuresFromVisit(totalXp, questPoints) || moved;
    }

    /**
     * Whether a game chat line is Juna's eligibility notice.
     *
     * <p>Separate from {@link #markEligible} so the caller can test every game message
     * cheaply before reading a clock.
     */
    public static boolean isEligibilityMessage(String message) {
        return message != null && message.contains(ELIGIBLE_MESSAGE);
    }

    /**
     * Record that Juna has said the player may drink.
     *
     * <p>That line reports both gates, so it outranks anything derived here and can bring
     * an unknown clock straight to ready.
     *
     * @return true if the reset time moved, so the caller should persist it
     */
    public boolean markEligible(long nowEpochSec) {
        boolean cleared = clearAdventures();
        return setReadyNow(nowEpochSec) || cleared;
    }

    /**
     * The wait stated by the Tears of Guthix page of the Quest Journal, or
     * {@link #JOURNAL_DAYS_UNREAD} if the page does not state one this can be sure of.
     *
     * <p>Refuses rather than guesses: an unread page leaves the clock unchanged, while a
     * wrong number would show for a week as the game's own.
     *
     * <p>"In N days" is taken wherever it appears. A bare "N days" is taken only when the
     * page states a single day figure, so a sentence about drinking once every seven days
     * is not read as a fresh week. "Tomorrow" counts as one day, as in Juna's dialogue.
     *
     * @param journalText the page's text, its widget tags already stripped
     */
    public static int parseJournalDays(String journalText) {
        if (journalText == null || journalText.isEmpty()) {
            return JOURNAL_DAYS_UNREAD;
        }

        Matcher wait = JOURNAL_WAIT.matcher(journalText);
        if (wait.find()) {
            return validJournalDays(wait.group(1));
        }

        Matcher any = JOURNAL_ANY_DAYS.matcher(journalText);
        String only = null;
        while (any.find()) {
            if (only != null && !only.equals(any.group(1))) {
                return JOURNAL_DAYS_UNREAD;
            }
            only = any.group(1);
        }
        if (only != null) {
            return validJournalDays(only);
        }
        return JOURNAL_TOMORROW.matcher(journalText).find() ? 1 : JOURNAL_DAYS_UNREAD;
    }

    /**
     * Fold in a line of Juna's dialogue.
     *
     * <p>A line can move the clock and the adventuring baseline together.
     *
     * @param totalXp     experience across all skills at this moment
     * @param questPoints the player's quest points at this moment
     * @return true if anything moved, so the caller should persist it
     */
    public boolean applyJunaLine(JunaLine line, long nowEpochSec, long totalXp, int questPoints) {
        if (line == null) {
            return false;
        }

        boolean moved;
        if (line.lettingYouIn) {
            // Both gates pass, and the visit she is opening the cave for will re-arm them.
            moved = markEligible(nowEpochSec);
        } else {
            moved = setWaitDays(line.daysRemaining, nowEpochSec);
        }

        if (line.wantsAdventures) {
            moved |= noteAdventuresNeeded(totalXp, questPoints);
        } else if (line.adventuresDone) {
            moved |= clearAdventures();
        }
        return moved;
    }

    /**
     * Arm the totals the adventuring gate is measured against, from Juna refusing.
     *
     * <p>Measured from her refusal, because the game reports no progress toward the gate.
     * The indicator can therefore clear late but never early.
     *
     * <p>A second refusal does not re-arm it, since re-stamping would erase progress earned
     * since the first. The exception is a refusal while the stored baseline reads met: her
     * answer wins.
     *
     * @return true if the baseline moved, so the caller should persist it
     */
    public boolean noteAdventuresNeeded(long totalXp, int questPoints) {
        if (totalXp < 0 || questPoints < 0) {
            return false;
        }
        Adventures owed = adventures;
        if (owed != null && !owed.metBy(totalXp, questPoints)) {
            return false;
        }
        adventures = new Adventures(totalXp, questPoints);
        return true;
    }

    /**
     * Arm the gate from a visit. The game counts the gate from entering the cave, so these
     * totals match its own; experience from the tears counts toward the next visit.
     *
     * @return true if the baseline moved, so the caller should persist it
     */
    public boolean armAdventuresFromVisit(long totalXp, int questPoints) {
        if (totalXp < 0 || questPoints < 0) {
            return false;
        }
        adventures = new Adventures(totalXp, questPoints);
        return true;
    }

    /** Forget the baseline, on evidence the gate is met. */
    public boolean clearAdventures() {
        if (adventures == null) {
            return false;
        }
        adventures = null;
        return true;
    }

    /**
     * What one line of Juna's says about the two gates.
     *
     * <p>Where a line reads as both, {@link #applyJunaLine} lets the refusal win. A gate
     * wrongly left standing costs less than one wrongly cleared, which sends the player down
     * to the Chasm of Tears to be turned away.
     *
     * @see #parseJunaLine
     */
    @Value
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class JunaLine {

        /** Days she says are left, or {@link #JOURNAL_DAYS_UNREAD} if she did not say. */
        int daysRemaining;

        /** Whether she is opening the cave, which answers both gates at once. */
        boolean lettingYouIn;

        /** Whether she is asking for new adventures before the next visit. */
        boolean wantsAdventures;

        /** Whether she says the adventuring gate is met. */
        boolean adventuresDone;
    }

    /**
     * Read a line of dialogue as Juna's, or null if it says nothing about either gate.
     *
     * <p>She answers the "Story" option with one of four endings, which state the exact
     * wait, whether the adventuring gate is in the way, or (when she opens the cave) that
     * neither is.
     *
     * <p>The choice of ending is itself evidence. Per the wiki's Juna transcript, "it has not
     * been long since your last visit" is reachable only with the adventuring gate met, and
     * "come again in N days if you have had more adventures by then" only with it unmet.
     *
     * <p>An attributed line must be hers: dialogue arrives as {@code Juna|...}. Only the
     * speakerless message box matching {@code JUNA_GATE_BOX} is accepted unattributed.
     *
     * @param dialogue one dialogue or message-box line, its widget tags already stripped
     */
    public static JunaLine parseJunaLine(String dialogue) {
        if (dialogue == null || dialogue.isEmpty()) {
            return null;
        }

        String said = junaSaid(dialogue);
        if (said == null) {
            return JUNA_GATE_BOX.matcher(dialogue).find()
                ? new JunaLine(JOURNAL_DAYS_UNREAD, false, true, false)
                : null;
        }

        boolean lettingYouIn = JUNA_LETTING_YOU_IN.matcher(said).find();
        boolean wantsAdventures = JUNA_WANTS_ADVENTURES.matcher(said).find();
        boolean adventuresDone = lettingYouIn || JUNA_ONLY_THE_WAIT.matcher(said).find();

        int days = JOURNAL_DAYS_UNREAD;
        Matcher wait = JUNA_WAIT.matcher(said);
        if (wait.find()) {
            days = wait.group(1) != null ? 1 : validJournalDays(wait.group(2));
        } else if (JUNA_WAITED_BUT_IDLE.matcher(said).find()) {
            // She says this only once the week is up, so the clock is ready and the
            // adventuring gate alone remains.
            days = 0;
        }

        if (days == JOURNAL_DAYS_UNREAD && !lettingYouIn && !wantsAdventures && !adventuresDone) {
            return null;
        }
        return new JunaLine(days, lettingYouIn, wantsAdventures, adventuresDone);
    }

    /**
     * Whether a dialogue line is attributed to Juna, whatever it says.
     *
     * <p>Used to log her lines the parser could not read.
     */
    public static boolean isJunaSpeaking(String dialogue) {
        return junaSaid(dialogue) != null;
    }

    /**
     * What Juna said, or null if the line is not attributed to her, including a line with
     * no speaker. The parser and the unread-line logging share this attribution rule.
     */
    private static String junaSaid(String dialogue) {
        if (dialogue == null) {
            return null;
        }
        int bar = dialogue.indexOf('|');
        if (bar <= 0 || !JUNA.equalsIgnoreCase(dialogue.substring(0, bar).trim())) {
            return null;
        }
        return dialogue.substring(bar + 1);
    }

    /** Forget the reset time, the adventuring baseline and any visit in progress. */
    public void clear() {
        nextAvailableEpochSec = 0L;
        adventures = null;
        visitInProgress = false;
    }

    /** Restore the reset time saved for this RS profile. */
    public void loadPersisted(long savedNextAvailableEpochSec) {
        nextAvailableEpochSec = Math.max(0L, savedNextAvailableEpochSec);
    }

    /** The reset time to save, or 0 when there is nothing worth saving. */
    public long exportForPersist() {
        return nextAvailableEpochSec;
    }

    /**
     * Restore the adventuring baseline: {@code {totalXp, questPoints}}, or null if the
     * profile has none stored.
     */
    public void loadPersistedAdventures(long[] saved) {
        adventures = saved == null || saved.length != 2 || saved[0] < 0
            ? null
            : new Adventures(saved[0], (int) saved[1]);
    }

    /** The adventuring baseline to save, or null when nothing says the gate is unmet. */
    public long[] exportAdventuresForPersist() {
        Adventures owed = adventures;
        return owed == null ? null : new long[]{owed.xp, owed.questPoints};
    }

    /**
     * Set the wait to a stated number of days, from the journal page or from Juna.
     *
     * <p>Both state the server's own count, so they outrank the visit stamped from the tick
     * bar and may move the clock in either direction. The day boundary matches
     * {@link #observeVisit}.
     *
     * <p>Only the day count is taken from the journal. The page states the adventuring gate
     * but not when it was last satisfied, so arming totals from it could restart a gate the
     * player already beat. A cave entry or Juna's refusal arms it instead.
     *
     * @param daysRemaining a count from {@link #parseJournalDays} or a line of Juna's
     * @return true if the reset time moved, so the caller should persist it
     */
    public boolean setWaitDays(int daysRemaining, long nowEpochSec) {
        if (daysRemaining < 0 || daysRemaining > WEEK_DAYS) {
            return false;
        }
        if (daysRemaining == 0) {
            return setReadyNow(nowEpochSec);
        }
        long next = startOfUtcDay(nowEpochSec) + daysRemaining * DAY_SECONDS;
        if (next == nextAvailableEpochSec) {
            return false;
        }
        nextAvailableEpochSec = next;
        return true;
    }

    /** Bring the clock to ready, leaving the adventuring gate exactly as it was. */
    private boolean setReadyNow(long nowEpochSec) {
        long next = nextAvailableEpochSec;
        if (next > 0 && nowEpochSec >= next) {
            return false;
        }
        nextAvailableEpochSec = nowEpochSec;
        return true;
    }

    /** A captured day figure, or unread if it is longer than a week Juna could impose. */
    private static int validJournalDays(String digits) {
        int days = Integer.parseInt(digits);
        return days > WEEK_DAYS ? JOURNAL_DAYS_UNREAD : days;
    }

    private static long startOfUtcDay(long epochSec) {
        return Math.floorDiv(epochSec, DAY_SECONDS) * DAY_SECONDS;
    }
}
