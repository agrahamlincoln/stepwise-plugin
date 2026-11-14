package com.stepwise.engine;

import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.gameval.VarbitID;

/**
 * The Kingdom of Miscellania's approval, coffer and uncollected resources, projected to today.
 *
 * <p>The client receives all three at login, wherever the player stands, but they describe the
 * kingdom as of its last server update. That update can run at login or only on a visit, so an
 * observation is dated by the day its values last <em>changed</em>, as RuneLite's own Kingdom
 * plugin does: a login-time update changes them and dates them today, and a lazy one leaves
 * them alone, so the old date still fits.
 *
 * <p>From that date the kingdom is replayed one UTC day at a time. Each day withdraws 10% of the
 * coffer up to a cap, turns the coins withdrawn and the approval into resource points, and decays
 * approval. After 30 days without a visit the kingdom stops until the player returns.
 */
public class KingdomTracker {

    /** Key this quest's completion is stored under in {@link PlayerState#getQuests()}. */
    public static final String QUEST_NAME = "Throne of Miscellania";

    /** Raises the worker count, the withdrawal cap, and slows approval decay. */
    public static final String ROYAL_TROUBLE = "Royal Trouble";

    public static final int APPROVAL_VARBIT = VarbitID.MISC_APPROVAL;
    public static final int COFFER_VARBIT = VarbitID.MISC_COFFERS;

    /** The uncollected resource points. Named for the bits it occupies, but it is the whole value. */
    public static final int POOL_VARBIT = VarbitID.MISC_RESTOTAL_LOWBITS;

    /** The hardwood choice on the management screen; see {@link Hardwood}. */
    public static final int HARDWOOD_TYPE_VARBIT = VarbitID.MISC_RAREWOOD_TYPE;

    /** The Herbs labour's choice: 0 herbs, 1 flax. */
    public static final int HERBS_OR_FLAX_VARBIT = VarbitID.MISC_HERBS_OR_FLAX;

    /**
     * A form of labour on the management screen, with the varbit counting its workers, 0–10.
     * Each varbit draws its own category's pips on that screen (RuneLite's {@code VarbitID}
     * names them {@code MISC_POINTS_*}).
     */
    @Getter
    @AllArgsConstructor
    public enum Labour {
        MINING(VarbitID.MISC_POINTS_MINE),
        WOOD(VarbitID.MISC_POINTS_WOOD),
        HERBS(VarbitID.MISC_POINTS_HERB),
        FISHING(VarbitID.MISC_POINTS_FISH),
        HARDWOOD(VarbitID.MISC_POINTS_RAREWOOD),
        FARM(VarbitID.MISC_POINTS_FARM);

        private final int varbit;
    }

    /** Every varbit the tracker reads. */
    public static final int[] VARBIT_IDS = IntStream.concat(
            IntStream.of(APPROVAL_VARBIT, COFFER_VARBIT, POOL_VARBIT, HARDWOOD_TYPE_VARBIT,
                    HERBS_OR_FLAX_VARBIT),
            Arrays.stream(Labour.values()).mapToInt(Labour::getVarbit)).toArray();

    /** The guide's hardwood crew: the most one category takes. */
    static final int HARDWOOD_CREW = 10;

    /**
     * Points one day earns at a full coffer and 100% approval after Royal Trouble: the day the
     * wiki's yield table describes. Items per point are the same before Royal Trouble (595 maple
     * from 762 points, 892 from 1,143), so the table converts any pool.
     */
    private static final int TABLE_DAY_POINTS = 1_143;

    /**
     * The hardwood choice, in varbit order. The management screen's three radio buttons are
     * {@code choosemahogany}, {@code chooseteak} and {@code chooseboth}, lit at 0, 1 and 2.
     */
    @Getter
    @AllArgsConstructor
    public enum Hardwood {
        MAHOGANY("mahogany"),
        TEAK("teak"),
        BOTH("both");

        private final String label;

        /** The choice as advice names it: "both" alone does not say which woods. */
        String woods() {
            return this == BOTH ? "teak and mahogany" : label;
        }

        /** The choice a varbit value stands for, or null for a value the screen has no button for. */
        static Hardwood fromVarbit(int value) {
            Hardwood[] all = values();
            return value >= 0 && value < all.length ? all[value] : null;
        }

        /** The choice a guide parameter names, or null for anything else. */
        public static Hardwood named(String label) {
            for (Hardwood h : values()) {
                if (h.label.equalsIgnoreCase(label)) {
                    return h;
                }
            }
            return null;
        }
    }

    /** What the workers are set to. Immutable, so a reader never sees half of it. */
    private static final class Allocation {

        /** Workers per {@link Labour}, by ordinal. */
        private final int[] workers;
        private final Hardwood hardwood;
        private final boolean flax;

        private Allocation(int[] workers, Hardwood hardwood, boolean flax) {
            this.workers = workers;
            this.hardwood = hardwood;
            this.flax = flax;
        }

        int on(Labour labour) {
            return workers[labour.ordinal()];
        }
    }

    /** One item a collection would hand over, estimated. */
    @Value
    public static class Yield {
        String item;
        int quantity;
    }

    private static final int MISCELLANIA_REGION = 10044;
    private static final int ETCETERIA_REGION = 10300;

    static final int MAX_APPROVAL = 127;

    /** Approval never decays below 25%. */
    static final int APPROVAL_FLOOR = 32;

    /** The most resource points one collection holds: the varbit's 18 bits. */
    static final int POOL_CAP = 262_143;

    /** Days without a visit after which the kingdom stops withdrawing and producing. */
    static final int PAUSE_DAYS = 30;

    /**
     * At or below this the trip is due. A week of decay after Royal Trouble lands here, which is
     * the guide's "visit at least once a week".
     */
    public static final int DUE_APPROVAL_PERCENT = 93;

    private static final int WITHDRAWAL_CAP = 50_000;
    private static final int WITHDRAWAL_CAP_ROYAL_TROUBLE = 75_000;

    /**
     * Daily decay in thousandths of an approval point: 2.5% of 127 before Royal Trouble, 1% after.
     * Thousandths keep the replay in integers, so a week of decay lands on the same percent every
     * run.
     */
    private static final int DECAY_MILLI = 3175;
    private static final int DECAY_MILLI_ROYAL_TROUBLE = 1270;

    private static final long DAY_SECONDS = 86400L;

    private static final long NO_VISIT = -1L;

    /** The last observation, or null before the first. One object, so readers never see halves. */
    private volatile State state;

    /**
     * The worker allocation this session, or null until read. Not persisted: the client sends it
     * at every login, and advice about a setting nobody has read this session could be stale.
     */
    private volatile Allocation allocation;

    private static final class State {

        private final int approval;
        private final int coffer;
        private final int pool;

        /** UTC epoch day the three values last changed. */
        private final long observedDay;

        /**
         * UTC epoch day of the first observation. Unlike {@link #observedDay} it never moves, so
         * it can stand in for a visit not yet seen without sliding the pause later.
         */
        private final long firstObservedDay;

        /** UTC epoch day the player last stood in the kingdom, or {@link #NO_VISIT}. */
        private final long lastVisitDay;

        /**
         * Whether the player has held approval at 100%. Until then the guide is not yet asking
         * for upkeep, so falling approval is not a reason to go.
         */
        private final boolean maintained;

        private State(int approval, int coffer, int pool, long observedDay, long firstObservedDay,
                      long lastVisitDay, boolean maintained) {
            this.approval = approval;
            this.coffer = coffer;
            this.pool = pool;
            this.observedDay = observedDay;
            this.firstObservedDay = firstObservedDay;
            this.lastVisitDay = lastVisitDay;
            this.maintained = maintained;
        }
    }

    /** The kingdom as projected to one day. */
    @Value
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Reading {

        int approvalPercent;

        int coffer;

        /** Uncollected resource points. */
        int pool;

        /** Whole UTC days since the player last stood in the kingdom, or -1 if never seen. */
        int daysSinceVisit;

        /** Whether 30 days have passed without a visit, so the kingdom has stopped. */
        boolean paused;

        /** Whether the coffer is under ten days' cap, below which the daily yield shrinks. */
        boolean cofferLow;

        /** Whole days until the trip is due; zero when it is due now. */
        int daysUntilDue;

        public boolean isDue() {
            return daysUntilDue == 0;
        }
    }

    /** Whether the player has finished the quest, and so has a kingdom at all. */
    public static boolean questCompleted(PlayerState state) {
        return state != null && state.getQuests().get(QUEST_NAME) == QuestStatus.COMPLETED;
    }

    public static boolean royalTroubleCompleted(PlayerState state) {
        return state != null && state.getQuests().get(ROYAL_TROUBLE) == QuestStatus.COMPLETED;
    }

    /** Whether a region is Miscellania or Etceteria, where standing counts as a visit. */
    public static boolean isKingdomRegion(int regionId) {
        return regionId == MISCELLANIA_REGION || regionId == ETCETERIA_REGION;
    }

    /**
     * Fold in the three values as the client reports them now.
     *
     * <p>An approval of 0 is ignored: after the quest, approval never falls below 25%, so 0 means
     * the varbits have not loaded yet.
     *
     * @return true if anything moved, so the caller should persist it
     */
    public boolean observe(int approval, int coffer, int pool, long nowEpochSec) {
        if (approval <= 0) {
            return false;
        }
        State prev = state;
        if (prev != null && prev.approval == approval && prev.coffer == coffer
                && prev.pool == pool) {
            return false;
        }
        long today = utcDay(nowEpochSec);
        state = prev == null
                ? new State(approval, coffer, pool, today, today, NO_VISIT,
                        approval >= MAX_APPROVAL)
                : new State(approval, coffer, pool, today, prev.firstObservedDay,
                        prev.lastVisitDay, prev.maintained || approval >= MAX_APPROVAL);
        return true;
    }

    /**
     * Fold in the worker allocation as the client reports it now.
     *
     * <p>Leaves the allocation unread unless every allocation varbit is present. Reading a gap as
     * 0 would mean "no workers" and draw advice; a state without a scan has none of them.
     *
     * @param varbits the player's varbit values by id
     */
    public void observeAllocation(Map<Integer, Integer> varbits) {
        Integer hardwood = varbits.get(HARDWOOD_TYPE_VARBIT);
        Integer herbsOrFlax = varbits.get(HERBS_OR_FLAX_VARBIT);
        if (hardwood == null || herbsOrFlax == null) {
            return;
        }
        int[] workers = new int[Labour.values().length];
        for (Labour labour : Labour.values()) {
            Integer count = varbits.get(labour.varbit);
            if (count == null) {
                return;
            }
            workers[labour.ordinal()] = count;
        }
        allocation = new Allocation(workers, Hardwood.fromVarbit(hardwood), herbsOrFlax == 1);
    }

    /**
     * What a collection would hand over from this many resource points, under the allocation read
     * this session; empty before it is read. Only each job's main resource is estimated. The
     * extras are left out: herb seeds and the split of herbs by type have no rate on the wiki,
     * and the bird nests and gems that come with logs and coal are a few per hundred.
     *
     * <p>Each item scales the wiki's daily yield at 10 workers by the workers on it and by the pool
     * in days of {@link #TABLE_DAY_POINTS}. The allocation at collection decides the whole pool,
     * so this is what collecting now would give.
     */
    public List<Yield> collectionEstimate(int pool) {
        Allocation a = allocation;
        List<Yield> yields = new ArrayList<>();
        if (a == null || pool <= 0) {
            return yields;
        }
        addYield(yields, "coal", 546, a.on(Labour.MINING), pool);
        addYield(yields, "maple logs", 892, a.on(Labour.WOOD), pool);
        if (a.flax) {
            addYield(yields, "flax", 1250, a.on(Labour.HERBS), pool);
        } else {
            addYield(yields, "grimy herbs", 61, a.on(Labour.HERBS), pool);
        }
        // The kingdom hands fish over raw or cooked as the player chose, in the same amounts.
        addYield(yields, "raw or cooked tuna", 440, a.on(Labour.FISHING), pool);
        addYield(yields, "raw or cooked swordfish", 132, a.on(Labour.FISHING), pool);
        // A hardwood value the screen has no button for names no wood, so it estimates none
        // rather than guessing one.
        int hardwoodWorkers = a.on(Labour.HARDWOOD);
        if (a.hardwood == Hardwood.MAHOGANY) {
            addYield(yields, "mahogany logs", 223, hardwoodWorkers, pool);
        } else if (a.hardwood == Hardwood.TEAK) {
            addYield(yields, "teak logs", 301, hardwoodWorkers, pool);
        } else if (a.hardwood == Hardwood.BOTH) {
            addYield(yields, "teak logs", 121, hardwoodWorkers, pool);
            addYield(yields, "mahogany logs", 121, hardwoodWorkers, pool);
        }
        addYield(yields, "seeds", 481, a.on(Labour.FARM), pool);
        return yields;
    }

    private static void addYield(List<Yield> yields, String item, int dailyAtTenWorkers,
                                 int workers, int pool) {
        long quantity = (long) dailyAtTenWorkers * workers * pool / (10L * TABLE_DAY_POINTS);
        if (quantity > 0) {
            yields.add(new Yield(item, (int) quantity));
        }
    }

    /**
     * What to change so the hardwood workers match the guide, or null when they already do,
     * when the guide names no hardwood, or before the setting has been read.
     *
     * <p>Null before Royal Trouble too: the hardwood category opens with it, so a player who
     * skipped ahead without the quest could not act on the advice.
     *
     * @param wanted       the hardwoods the guide accepts at this stage, first choice first
     * @param royalTrouble whether the player has finished Royal Trouble
     */
    public String hardwoodAdvice(List<String> wanted, boolean royalTrouble) {
        Allocation a = allocation;
        if (a == null || wanted == null || !royalTrouble) {
            return null;
        }
        List<Hardwood> accepted = new ArrayList<>();
        for (String label : wanted) {
            Hardwood h = Hardwood.named(label);
            if (h != null) {
                accepted.add(h);
            }
        }
        if (accepted.isEmpty()) {
            return null;
        }
        // A crew already on an accepted hardwood keeps it: switching to mahogany early is the
        // guide's own advice, and a short crew should not be told to undo it. With nobody on
        // hardwood the choice is only the screen's default, so the first choice stands.
        int workers = a.on(Labour.HARDWOOD);
        boolean onAccepted = workers > 0 && accepted.contains(a.hardwood);
        Hardwood target = onAccepted ? a.hardwood : accepted.get(0);
        if (workers < HARDWOOD_CREW) {
            return "Put " + HARDWOOD_CREW + " workers on " + target.woods();
        }
        return onAccepted ? null : "Switch hardwood to " + target.woods();
    }

    /**
     * Record that the player is standing in the kingdom today. Only the day matters, since the
     * pause counts whole days.
     *
     * <p>Before any observation there is nothing to attach the visit to, so it is dropped. The
     * login scan observes before the first region change, so this costs nothing in practice.
     *
     * @return true if the visit day moved, so the caller should persist it
     */
    public boolean markVisit(long nowEpochSec) {
        State prev = state;
        long today = utcDay(nowEpochSec);
        if (prev == null || prev.lastVisitDay == today) {
            return false;
        }
        state = new State(prev.approval, prev.coffer, prev.pool, prev.observedDay,
                prev.firstObservedDay, today, prev.maintained);
        return true;
    }

    /** Project the kingdom to now, or null before anything has been observed. */
    public Reading readAt(long nowEpochSec, boolean royalTrouble) {
        State s = state;
        if (s == null) {
            return null;
        }
        long today = utcDay(nowEpochSec);
        int cap = royalTrouble ? WITHDRAWAL_CAP_ROYAL_TROUBLE : WITHDRAWAL_CAP;
        int decay = royalTrouble ? DECAY_MILLI_ROYAL_TROUBLE : DECAY_MILLI;

        // Until a visit is seen, the last visit is taken as the first observation, the latest
        // it can be, so the pause shown is the latest it can come.
        long visitDay = s.lastVisitDay != NO_VISIT ? s.lastVisitDay : s.firstObservedDay;
        long lastRunningDay = visitDay + PAUSE_DAYS;

        Replay now = new Replay(s);
        for (long day = s.observedDay + 1; day <= Math.min(today, lastRunningDay); day++) {
            now.advanceDay(cap, decay);
        }

        // The pause is always a reason to go. Falling approval is one only once the player
        // keeps it up, and it can only bring the trip forward.
        int daysUntilDue = (int) Math.max(0, lastRunningDay - today);
        if (s.maintained) {
            Replay ahead = now.copy();
            for (int d = 0; d < daysUntilDue; d++) {
                if (ahead.percent() <= DUE_APPROVAL_PERCENT) {
                    daysUntilDue = d;
                    break;
                }
                ahead.advanceDay(cap, decay);
            }
        }

        int daysSinceVisit = s.lastVisitDay != NO_VISIT ? (int) (today - s.lastVisitDay) : -1;
        return new Reading(now.percent(), (int) now.coffer, (int) now.pool, daysSinceVisit,
                today > lastRunningDay, now.coffer < 10L * cap, daysUntilDue);
    }

    /** The kingdom's three values mid-replay. */
    private static final class Replay {

        private int approvalMilli;
        private long coffer;
        private long pool;

        private Replay(State s) {
            this(s.approval * 1000, s.coffer, s.pool);
        }

        private Replay(int approvalMilli, long coffer, long pool) {
            this.approvalMilli = approvalMilli;
            this.coffer = coffer;
            this.pool = pool;
        }

        Replay copy() {
            return new Replay(approvalMilli, coffer, pool);
        }

        /**
         * One UTC day: withdraw from the coffer, turn the coins and today's approval into
         * resource points, then decay approval.
         */
        void advanceDay(int cap, int decayMilli) {
            long withdrawn = coffer > 10L * cap ? cap : coffer / 10;
            coffer -= withdrawn;
            long points = (withdrawn * 100 / 8333) * (approvalMilli / 1000) / 100;
            pool = Math.min(POOL_CAP, pool + points);
            approvalMilli = Math.max(APPROVAL_FLOOR * 1000, approvalMilli - decayMilli);
        }

        int percent() {
            return KingdomTracker.percent(approvalMilli);
        }
    }

    private static int percent(int approvalMilli) {
        return approvalMilli * 100 / (MAX_APPROVAL * 1000);
    }

    private static long utcDay(long epochSec) {
        return Math.floorDiv(epochSec, DAY_SECONDS);
    }

    /** The observation as one line for the RS profile, or null before the first. */
    public String exportForPersist() {
        State s = state;
        if (s == null) {
            return null;
        }
        return s.approval + ":" + s.coffer + ":" + s.pool + ":" + s.observedDay + ":"
                + s.firstObservedDay + ":" + s.lastVisitDay + ":" + (s.maintained ? 1 : 0);
    }

    /** Forget the observation and the allocation setting. */
    public void clear() {
        state = null;
        allocation = null;
    }

    /** Restore an {@link #exportForPersist()} line. Anything unreadable leaves no observation. */
    public void loadPersisted(String stored) {
        state = parse(stored);
        // A new account's setting arrives with its login scan; the last account's must not
        // stand in until then.
        allocation = null;
    }

    private static State parse(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        String[] parts = stored.split(":");
        if (parts.length != 7) {
            return null;
        }
        try {
            return new State(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]), Long.parseLong(parts[5]), "1".equals(parts[6]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
