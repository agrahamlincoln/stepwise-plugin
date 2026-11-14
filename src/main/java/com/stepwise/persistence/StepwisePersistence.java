package com.stepwise.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.stepwise.engine.BirdhouseTracker;
import com.stepwise.guide.StepId;
import com.stepwise.model.PlayerState;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Saves and restores Stepwise's state through a {@link ConfigStore}. Progress lives under the
 * RS profile so each OSRS account keeps its own; the welcome screen's last-session summary
 * lives under global keys, readable before any account logs in.
 */
@Slf4j
public class StepwisePersistence {

    private static final String CONFIG_GROUP = "stepwise";
    /** Appended to a key whose saved value did not parse, to keep that value aside. */
    static final String UNREADABLE_SUFFIX = ".unreadable";
    private static final String KEY_PLAYER_STATE = "playerState";
    private static final String KEY_COMPLETED_STEPS = "completedSteps";
    private static final String KEY_SKIPPED_STEPS = "skippedSteps";
    private static final String KEY_SUPPRESSED_STEPS = "suppressedSteps";
    private static final String KEY_RECENT_MANUAL_STEPS = "recentManualSteps";
    private static final String KEY_GUIDE_VERSION = "guideVersion";
    private static final String KEY_BIRDHOUSE_PREFIX = "birdhouse.";
    private static final String KEY_TEARS_OF_GUTHIX_RESET = "tearsOfGuthixReset";
    private static final String KEY_TEARS_OF_GUTHIX_ADVENTURES = "tearsOfGuthixAdventures";
    private static final String KEY_KINGDOM = "kingdom";
    // Regular (non-RS-profile) config keys, readable before login: the welcome screen's
    // "last session" summary.
    private static final String KEY_LAST_PLAYER = "lastPlayer";
    private static final String KEY_LAST_STEP_INDEX = "lastStepIndex";
    private static final String KEY_LAST_TOTAL_STEPS = "lastTotalSteps";
    private static final String KEY_LAST_CHAPTER = "lastChapter";
    private static final String KEY_LAST_COMPLETED = "lastCompletedCount";
    private static final String KEY_LAST_MINUTES_DONE = "lastMinutesDone";
    private static final String KEY_LAST_TOTAL_MINUTES = "lastTotalMinutes";
    private static final String KEY_LAST_RS_PROFILE = "lastRsProfile";

    private static final long SAVE_DEBOUNCE_MS = 5000;

    private final ConfigStore store;
    private final Gson gson;
    /** The RS profile current at the last player-state load; null when there was none. */
    private String loadedProfileKey;

    private long lastSaveTime;
    private boolean dirty;

    public StepwisePersistence(ConfigStore store, Gson base) {
        this.store = store;
        this.gson = buildGson(base);
    }

    /**
     * Build the Gson used for every persisted value.
     *
     * <p>The {@link Instant} adapter fixes the stored format. {@link PlayerState} holds
     * {@code Instant} fields, saved as epoch seconds. {@code base} is the client's injected
     * Gson, which registers its own {@code Instant} adapter; the one registered here is added
     * later and wins, so the saved format does not depend on the client's choice.
     */
    static Gson buildGson(Gson base) {
        return base.newBuilder()
                .registerTypeAdapter(StepId.class, new StepId.TypeAdapter())
                .registerTypeAdapter(Instant.class,
                        (JsonSerializer<Instant>) (src, type, ctx) ->
                                new JsonPrimitive(src.getEpochSecond()))
                .registerTypeAdapter(Instant.class,
                        (JsonDeserializer<Instant>) (json, type, ctx) ->
                                Instant.ofEpochSecond(json.getAsLong()))
                .create();
    }

    /**
     * Put back what Gson could not have set: the concurrent collections and the "not looked
     * at yet" sentinels.
     *
     * <p>{@link PlayerState} has no no-arg constructor, so Gson allocates it through
     * {@code Unsafe} and runs neither the constructor nor the field initializers: every
     * transient primitive arrives as 0. For the Slayer task count, 0 is a real reading (see
     * {@link PlayerState#SLAYER_TASK_COUNT_UNREAD}).
     *
     * <p>Login resolves the whole guide before the first tick samples the count. A restored
     * zero would make {@code noSlayerTask} latch for a player holding a task, and the retract
     * pass only reconsiders inventory conditions, so the latch would persist.
     *
     * <p>Null-tolerant: a profile with no saved state stays null.
     */
    static PlayerState finishRestore(PlayerState restored) {
        if (restored != null) {
            restored.useConcurrentCollections();
            restored.setSlayerTaskCount(PlayerState.SLAYER_TASK_COUNT_UNREAD);
            // Re-sum the derived skill totals rather than trusting whatever the JSON held.
            restored.recalculateTotalLevel();
        }
        return restored;
    }

    /**
     * Whether this persistence only reads, so whatever an engine over it saves is dropped:
     * the logged-out page's view of the last account.
     */
    public boolean isReadOnly() {
        return store != null && store.isReadOnly();
    }

    /** Whether the RS profile holds saved player state, without parsing it. */
    public boolean hasSavedPlayerState() {
        String json = store.getRSProfileConfiguration(CONFIG_GROUP, KEY_PLAYER_STATE);
        return json != null && !json.isEmpty();
    }

    public PlayerState loadPlayerState() {
        // Taken even when null. A brand-new account has no RS profile yet when it loads, and
        // keeping the previous account's key would pair this player's name with that profile.
        loadedProfileKey = store.rsProfileKey();
        return finishRestore(loadJson(KEY_PLAYER_STATE, PlayerState.class, null));
    }

    public Set<StepId> loadCompletedSteps() {
        return loadStepIdSet(KEY_COMPLETED_STEPS);
    }

    public Set<StepId> loadSkippedSteps() {
        return loadStepIdSet(KEY_SKIPPED_STEPS);
    }

    public Set<StepId> loadSuppressedSteps() {
        return loadStepIdSet(KEY_SUPPRESSED_STEPS);
    }

    /**
     * Persist the latest Mark Done and Skip clicks, newest first, so Recent can still offer
     * their Undo after a relog. Written on each click rather than with the debounced save,
     * since the list is a handful of ids.
     */
    public void saveRecentManualSteps(List<StepId> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) {
            store.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_RECENT_MANUAL_STEPS);
            return;
        }
        store.setRSProfileConfiguration(CONFIG_GROUP, KEY_RECENT_MANUAL_STEPS,
                gson.toJson(newestFirst));
    }

    /**
     * The latest Mark Done and Skip clicks, newest first; empty when none are saved or the
     * saved list does not parse.
     */
    public List<StepId> loadRecentManualSteps() {
        return new ArrayList<>(loadJson(KEY_RECENT_MANUAL_STEPS,
                new TypeToken<List<StepId>>() {}.getType(), List.<StepId>of()));
    }

    public String loadGuideVersion() {
        return store.getRSProfileConfiguration(CONFIG_GROUP, KEY_GUIDE_VERSION);
    }

    /** One RS-profile key as a number, or 0 when it is absent or not a number. */
    private long loadLong(String key) {
        try {
            return Long.parseLong(store.getRSProfileConfiguration(CONFIG_GROUP, key));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Mark state dirty; the write happens on the next debounced save or forceSave(). */
    public void markDirty() {
        this.dirty = true;
    }

    /** Save if dirty and the debounce period has elapsed. Call from a periodic handler such as GameTick. */
    public void saveIfNeeded(PlayerState playerState, Set<StepId> completed,
            Set<StepId> skipped, Set<StepId> suppressed) {
        if (!dirty) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < SAVE_DEBOUNCE_MS) {
            return;
        }
        forceSave(playerState, completed, skipped, suppressed);
    }

    /** Save immediately, ignoring the debounce. Call on shutDown() or logout. */
    public void forceSave(PlayerState playerState, Set<StepId> completed,
            Set<StepId> skipped, Set<StepId> suppressed) {
        try {
            if (playerState != null) {
                store.setRSProfileConfiguration(CONFIG_GROUP, KEY_PLAYER_STATE,
                        gson.toJson(playerState));
            }
            store.setRSProfileConfiguration(CONFIG_GROUP, KEY_COMPLETED_STEPS,
                    gson.toJson(completed));
            store.setRSProfileConfiguration(CONFIG_GROUP, KEY_SKIPPED_STEPS,
                    gson.toJson(skipped));
            store.setRSProfileConfiguration(CONFIG_GROUP, KEY_SUPPRESSED_STEPS,
                    gson.toJson(suppressed));

            lastSaveTime = System.currentTimeMillis();
            dirty = false;
            log.debug("Stepwise state saved ({} completed, {} skipped, {} suppressed)",
                    completed.size(), skipped.size(), suppressed.size());
        } catch (Exception e) {
            log.warn("Failed to save Stepwise state", e);
        }
    }

    public void saveGuideVersion(String version) {
        store.setRSProfileConfiguration(CONFIG_GROUP, KEY_GUIDE_VERSION, version);
    }

    /**
     * Persist bird house timers per RS profile, one key per space, as
     * "varpValue:seededAtEpochSec". Clears all four first so an emptied house
     * does not linger with a stale timestamp.
     */
    public void saveBirdhouses(Collection<BirdhouseTracker.PersistedEntry> entries) {
        for (BirdhouseTracker.BirdhouseSpace space : BirdhouseTracker.BirdhouseSpace.values()) {
            store.unsetRSProfileConfiguration(CONFIG_GROUP,
                    KEY_BIRDHOUSE_PREFIX + space.getVarpId());
        }
        if (entries == null) {
            return;
        }
        for (BirdhouseTracker.PersistedEntry e : entries) {
            store.setRSProfileConfiguration(CONFIG_GROUP,
                    KEY_BIRDHOUSE_PREFIX + e.getVarpId(),
                    e.getVarpValue() + ":" + e.getSeededAtEpochSec());
        }
    }

    /** Load persisted bird house timers for the current RS profile. */
    public Collection<BirdhouseTracker.PersistedEntry> loadBirdhouses() {
        List<BirdhouseTracker.PersistedEntry> out = new ArrayList<>();
        for (BirdhouseTracker.BirdhouseSpace space : BirdhouseTracker.BirdhouseSpace.values()) {
            String stored = store.getRSProfileConfiguration(CONFIG_GROUP,
                    KEY_BIRDHOUSE_PREFIX + space.getVarpId());
            if (stored == null || stored.isEmpty()) {
                continue;
            }
            String[] parts = stored.split(":");
            if (parts.length != 2) {
                continue;
            }
            try {
                int varp = Integer.parseInt(parts[0]);
                long ts = Long.parseLong(parts[1]);
                out.add(new BirdhouseTracker.PersistedEntry(space.getVarpId(), varp, ts));
            } catch (NumberFormatException ex) {
                // ignore malformed entry
            }
        }
        return out;
    }

    /**
     * Persist when Juna next resets, per RS profile, as epoch seconds. The wait is a week,
     * so most of it passes while the player is logged out.
     */
    public void saveTearsOfGuthixReset(long nextAvailableEpochSec) {
        store.setRSProfileConfiguration(CONFIG_GROUP, KEY_TEARS_OF_GUTHIX_RESET,
                Long.toString(nextAvailableEpochSec));
    }

    /** Load Juna's next reset, or 0 when no visit has been recorded for this profile. */
    public long loadTearsOfGuthixReset() {
        return loadLong(KEY_TEARS_OF_GUTHIX_RESET);
    }

    /**
     * Persist the totals Juna's adventuring gate is measured against, as
     * {@code {totalXp, questPoints}}, or null to forget them.
     *
     * <p>The gate can outlast the weekly wait, and only asking Juna again would re-arm it.
     */
    public void saveTearsOfGuthixAdventures(long[] baseline) {
        if (baseline == null || baseline.length != 2) {
            store.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_TEARS_OF_GUTHIX_ADVENTURES);
            return;
        }
        store.setRSProfileConfiguration(CONFIG_GROUP, KEY_TEARS_OF_GUTHIX_ADVENTURES,
                baseline[0] + ":" + baseline[1]);
    }

    /** Load that baseline, or null when nothing says the gate is unmet for this profile. */
    public long[] loadTearsOfGuthixAdventures() {
        return parseTearsOfGuthixAdventures(store.getRSProfileConfiguration(
                CONFIG_GROUP, KEY_TEARS_OF_GUTHIX_ADVENTURES));
    }

    /**
     * The stored baseline as {@code {totalXp, questPoints}}, or null for any value that is
     * not two numbers (absent, half-written or hand-edited).
     */
    static long[] parseTearsOfGuthixAdventures(String stored) {
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        int split = stored.indexOf(':');
        if (split <= 0) {
            return null;
        }
        try {
            return new long[]{
                Long.parseLong(stored.substring(0, split)),
                Long.parseLong(stored.substring(split + 1)),
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Persist the Kingdom of Miscellania observation, per RS profile, in
     * {@link com.stepwise.engine.KingdomTracker#exportForPersist()}'s format; null forgets it.
     */
    public void saveKingdom(String observation) {
        if (observation == null) {
            store.unsetRSProfileConfiguration(CONFIG_GROUP, KEY_KINGDOM);
            return;
        }
        store.setRSProfileConfiguration(CONFIG_GROUP, KEY_KINGDOM, observation);
    }

    /** Load that observation, or null when none has been recorded for this profile. */
    public String loadKingdom() {
        return store.getRSProfileConfiguration(CONFIG_GROUP, KEY_KINGDOM);
    }

    /**
     * Save a session summary to regular (non-profile) config, so the welcome screen can
     * show it before login.
     *
     * @param minutesDone  minutes of play the completed steps account for
     * @param totalMinutes the guide's whole length in minutes of play, or null when the guide
     *                     data carries no timings
     */
    public void saveSessionSummary(String playerName, int stepIndex, int totalSteps,
            String chapter, int completedCount, double minutesDone, Double totalMinutes) {
        try {
            if (totalMinutes != null && totalMinutes > 0) {
                store.setConfiguration(CONFIG_GROUP, KEY_LAST_MINUTES_DONE,
                        String.valueOf(minutesDone));
                store.setConfiguration(CONFIG_GROUP, KEY_LAST_TOTAL_MINUTES,
                        String.valueOf(totalMinutes));
            } else {
                store.unsetConfiguration(CONFIG_GROUP, KEY_LAST_MINUTES_DONE);
                store.unsetConfiguration(CONFIG_GROUP, KEY_LAST_TOTAL_MINUTES);
            }
            // The profile the account's progress is saved under, so the logged-out page can
            // read it back while RuneLite has no current profile. The key captured when the
            // progress loaded, because the client may already have dropped its current profile
            // by the time a logout save runs. The loaded key wins; the client's current profile
            // is the fallback.
            String profileKey = loadedProfileKey != null ? loadedProfileKey : store.rsProfileKey();
            if (profileKey != null) {
                store.setConfiguration(CONFIG_GROUP, KEY_LAST_RS_PROFILE, profileKey);
            } else {
                // With neither key, the old key goes: it would name another account's progress.
                store.unsetConfiguration(CONFIG_GROUP, KEY_LAST_RS_PROFILE);
            }
            store.setConfiguration(CONFIG_GROUP, KEY_LAST_PLAYER, playerName);
            store.setConfiguration(CONFIG_GROUP, KEY_LAST_STEP_INDEX,
                    String.valueOf(stepIndex));
            store.setConfiguration(CONFIG_GROUP, KEY_LAST_TOTAL_STEPS,
                    String.valueOf(totalSteps));
            store.setConfiguration(CONFIG_GROUP, KEY_LAST_CHAPTER,
                    chapter != null ? chapter : "");
            store.setConfiguration(CONFIG_GROUP, KEY_LAST_COMPLETED,
                    String.valueOf(completedCount));
        } catch (Exception e) {
            log.warn("Failed to save session summary", e);
        }
    }

    /** Load the last session summary, or null if there is none or it fails to parse. */
    public SessionSummary loadSessionSummary() {
        try {
            String player = store.getConfiguration(CONFIG_GROUP, KEY_LAST_PLAYER);
            if (player == null || player.isEmpty()) {
                return null;
            }
            String stepIdxStr = store.getConfiguration(CONFIG_GROUP, KEY_LAST_STEP_INDEX);
            String totalStr = store.getConfiguration(CONFIG_GROUP, KEY_LAST_TOTAL_STEPS);
            String chapter = store.getConfiguration(CONFIG_GROUP, KEY_LAST_CHAPTER);
            String completedStr = store.getConfiguration(CONFIG_GROUP, KEY_LAST_COMPLETED);

            int stepIndex = stepIdxStr != null ? Integer.parseInt(stepIdxStr) : 0;
            int totalSteps = totalStr != null ? Integer.parseInt(totalStr) : 0;
            int completedCount = completedStr != null ? Integer.parseInt(completedStr) : 0;
            String minutesDoneStr = store.getConfiguration(CONFIG_GROUP, KEY_LAST_MINUTES_DONE);
            String totalMinutesStr = store.getConfiguration(CONFIG_GROUP, KEY_LAST_TOTAL_MINUTES);
            // Both or neither: a summary without timings has neither. A bad value
            // drops only the timings, so the welcome page falls back to the step count.
            Double minutesDone = null;
            Double totalMinutes = null;
            if (minutesDoneStr != null && totalMinutesStr != null) {
                try {
                    minutesDone = Double.parseDouble(minutesDoneStr);
                    totalMinutes = Double.parseDouble(totalMinutesStr);
                } catch (NumberFormatException e) {
                    minutesDone = null;
                    totalMinutes = null;
                }
            }

            String profileKey = store.getConfiguration(CONFIG_GROUP, KEY_LAST_RS_PROFILE);
            return new SessionSummary(player, stepIndex, totalSteps, chapter, completedCount,
                    minutesDone, totalMinutes,
                    profileKey == null || profileKey.isEmpty() ? null : profileKey);
        } catch (Exception e) {
            log.warn("Failed to load session summary", e);
            return null;
        }
    }

    /** Snapshot of the last session, readable without an RS profile. */
    @lombok.Value
    public static class SessionSummary {
        String playerName;
        int stepIndex;
        int totalSteps;
        String chapter;
        int completedCount;
        /** Minutes of play done; null when the summary has no timings. */
        Double minutesDone;
        /** The guide's whole length in minutes of play; null when the summary has no timings. */
        Double totalMinutes;
        /**
         * The RS profile the account's progress is saved under; null when the summary
         * records none.
         */
        String rsProfileKey;
    }

    private Set<StepId> loadStepIdSet(String key) {
        return new HashSet<>(loadJson(key, new TypeToken<Set<StepId>>() {}.getType(), Set.<StepId>of()));
    }

    /**
     * One RS-profile key parsed as JSON, or {@code empty} when it is absent or does not parse.
     *
     * <p>A value that does not parse is copied to {@code <key>.unreadable} before the account
     * starts fresh, so the next save cannot destroy it and it can still be recovered by hand.
     * The first copy is kept if one exists, since it holds the progress from before the
     * failure. Starting fresh loses only steps the game cannot show: the first-login
     * fast-forward places the player again from the account itself.
     */
    private <T> T loadJson(String key, Type type, T empty) {
        String json = store.getRSProfileConfiguration(CONFIG_GROUP, key);
        if (json == null || json.isEmpty()) {
            return empty;
        }
        try {
            T result = gson.fromJson(json, type);
            return result != null ? result : empty;
        } catch (RuntimeException e) {
            String backupKey = key + UNREADABLE_SUFFIX;
            if (store.getRSProfileConfiguration(CONFIG_GROUP, backupKey) == null) {
                store.setRSProfileConfiguration(CONFIG_GROUP, backupKey, json);
            }
            log.warn("Saved '{}' does not parse; kept it as '{}' and starting fresh", key, backupKey, e);
            return empty;
        }
    }
}
