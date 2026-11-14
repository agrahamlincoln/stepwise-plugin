package com.stepwise.engine;

import com.stepwise.guide.CompletionCondition;
import com.stepwise.guide.CompositeMode;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.ItemLocation;
import com.stepwise.guide.MilestonesFile;
import com.stepwise.guide.NpcInteractions;
import com.stepwise.guide.StepId;
import com.stepwise.guide.StepStatus;
import com.stepwise.guide.WatchedObjectIds;
import com.stepwise.model.AccountType;
import com.stepwise.model.EquipmentSlot;
import com.stepwise.model.ItemStack;
import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import com.stepwise.persistence.StepwisePersistence;
import com.stepwise.snapshot.CompletionCriterion;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.DetailedStep;
import com.stepwise.sailing.BoatVarbits;
import com.stepwise.snapshot.GuidePosition;
import com.stepwise.snapshot.ResolvedStep;
import com.stepwise.snapshot.CurrentStepSummary;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Central orchestrator.
 * Holds PlayerState, StepwiseGuide, the current guide step, the timer trackers, and step overrides.
 * Each game event flows through here, mutates state, checks for auto-latch,
 * and rebuilds the dashboard snapshot.
 */
@Slf4j
public class CoreEngine {

    @Getter
    private final StepwiseGuide guide;

    /**
     * Volatile: the timer panel reads it on the EDT on its own clock, with no hand-off from
     * the client thread, so only the volatile write publishes a freshly loaded state whole.
     */
    @Getter
    private volatile PlayerState playerState;

    /**
     * The step the panel and overlay present as current: the last step to resolve ACTIVE. It
     * stays put on a pass with no ACTIVE step, and is null before the first resolve after a
     * login or account change.
     */
    private StepId currentStepId;

    @Getter
    private final BirdhouseTracker birdhouseTracker;

    @Getter
    private final TearsOfGuthixTracker tearsOfGuthixTracker;

    @Getter
    private final KingdomTracker kingdomTracker;

    private final Set<StepId> completedSteps;

    final Set<StepId> skippedSteps;

    /**
     * Steps the user explicitly undid. Suppresses auto-latch until the step is manually
     * re-completed, or until no pathway would latch it any more (see resolveAndRebuild).
     */
    final Set<StepId> suppressedSteps;

    /**
     * How many Mark Done and Skip clicks the Recent section keeps. Five covers a sitting's
     * worth of In Progress clicks (a farm run's background steps) and keeps Recent, with its
     * own two rows either side of the current step, within one screen.
     */
    static final int RECENT_MANUAL_LIMIT = 5;

    /**
     * The latest Mark Done and Skip clicks, newest first, at most {@link #RECENT_MANUAL_LIMIT}.
     * Recent lists them wherever they sit in the guide. Without this a step done from the In
     * Progress list, often far behind the current step, could never be undone. Saved with the
     * account, like the sets Undo edits, so a relog does not take the Undo away.
     */
    private final List<StepId> recentManualSteps;

    private final StepwisePersistence persistence;

    private final MilestonesFile milestonesFile;

    final Map<StepId, GuideStep> stepIndex;

    /**
     * Reverse prerequisite index: maps a step ID to all steps that list it as a prerequisite.
     * Used to auto-complete pure travel steps when a dependent step's condition is already met.
     */
    private final Map<StepId, Set<StepId>> prerequisiteOf;

    /**
     * Runs a Runnable on the client thread. EDT-callable mutation methods (markStepDone,
     * skipStep, etc.) route through it to serialize access to shared mutable state.
     */
    private Consumer<Runnable> clientThreadExecutor;

    /**
     * True if the persisted completedSteps set was empty when loadPersistedState() ran.
     * Captured before resolveAndRebuild(), whose per-step auto-latch would otherwise mask a
     * fresh install. onLogin() consumes it before running performMilestoneLatch(), so the latch
     * fires once.
     */
    private boolean noPersistedProgress;

    /** Test visibility: whether the fresh-install milestone latch is armed. */
    boolean isMilestoneLatchArmed() {
        return noPersistedProgress;
    }

    /**
     * Set by {@link #resetGuideProgress()} and consumed by StepwisePlugin on the next
     * game tick to trigger a full login re-scan (varbits, varps, milestone latch).
     * Volatile because it is written from the Swing EDT and read from the client thread.
     */
    private volatile boolean resetPending;

    /**
     * Set by {@link #afterEvent()} when game state changes. {@link #flushIfDirty()}
     * consumes it once per game tick, so a tick's events share one resolve pass.
     */
    private boolean resolveDirty;

    /** The object ids the loaded guide asks about — the only spawns worth recording. */
    private final WatchedObjectIds watchedObjects;
    private final NpcInteractions watchedNpcs;

    /** Watches the Slayer vars for the one tick a task is killed out. */
    private final SlayerTaskTracker slayerTasks = new SlayerTaskTracker();

    /**
     * Watched object ids seen since the client started, kept beside {@link #playerState}
     * rather than only in it.
     *
     * <p>The scene loads before the profile does. Spawn events for the login region arrive
     * before {@code StepwisePlugin.readAccount}, which replaces {@link #playerState} wholesale
     * with the copy read from config, so a sighting written straight into the state would be lost.
     * For a player who logs in at home and stays in the region, those are the only spawns.
     *
     * <p>Sightings accumulate here and are folded into the state each time it is
     * (re)assigned. Bounded by {@link #watchedObjects}, and idempotent to replay.
     */
    private final Set<Integer> objectsSeenThisSession = new HashSet<>();

    /**
     * NPC interactions observed this session, held and adopted as
     * {@link #objectsSeenThisSession} is. Bounded by {@link #watchedNpcs}.
     */
    private final Set<String> npcInteractionsSeenThisSession = new HashSet<>();

    @Getter
    private DashboardSnapshot lastSnapshot;

    /**
     * The steps a game-event flush latched while each was the active step, oldest first.
     * {@link #takeAutoCompletedSteps} hands them on and clears the list. A click flushes
     * between ticks, so a tick can collect more than one. Client thread only.
     */
    private final List<StepId> autoCompletedSteps = new ArrayList<>();

    /** Set by a login scan until the next flush, which reads the scanned state. */
    private boolean loginResolvePending;

    /**
     * Monotonic counter the panel compares to decide whether to redraw. Incremented when the
     * snapshot's content changes, when bird house timers change, and on a guide reset.
     */
    @Getter
    private long snapshotVersion;

    public CoreEngine(StepwiseGuide guide, StepwisePersistence persistence, MilestonesFile milestonesFile) {
        this.guide = guide;
        this.persistence = persistence;
        this.milestonesFile = milestonesFile;
        this.birdhouseTracker = new BirdhouseTracker();
        this.tearsOfGuthixTracker = new TearsOfGuthixTracker();
        this.kingdomTracker = new KingdomTracker();
        this.completedSteps = new HashSet<>();
        this.skippedSteps = new HashSet<>();
        this.suppressedSteps = new HashSet<>();
        this.recentManualSteps = new ArrayList<>();
        this.lastSnapshot = null;

        this.watchedObjects = WatchedObjectIds.fromGuide(guide);
        this.watchedNpcs = NpcInteractions.fromGuide(guide);
        this.stepIndex = new HashMap<>();
        this.prerequisiteOf = new HashMap<>();
        for (GuideStep step : guide.allSteps()) {
            stepIndex.put(step.getId(), step);
            if (step.getPrerequisites() != null) {
                for (StepId prereq : step.getPrerequisites()) {
                    prerequisiteOf.computeIfAbsent(prereq, k -> new HashSet<>()).add(step.getId());
                }
            }
        }
    }

    /**
     * Set the executor for routing EDT mutations to the client thread.
     * Must be called before any panel interaction (e.g. button clicks).
     */
    public void setClientThreadExecutor(Consumer<Runnable> executor) {
        this.clientThreadExecutor = executor;
    }

    public Set<StepId> getCompletedSteps() {
        return Collections.unmodifiableSet(completedSteps);
    }

    /**
     * Load the current RS profile's saved state, replacing whatever the engine held.
     *
     * <p>Runs at every login, not once per client run. The engine outlives a logout, so the
     * account logging in may not be the one that left.
     */
    public void loadPersistedState() {
        clearAccountState();

        PlayerState saved = persistence.loadPlayerState();
        if (saved != null) {
            this.playerState = saved;
            log.debug("Restored saved player state");
        }
        // The login scene's spawns are held in the session set; re-adopt them after the swap.
        adoptSightingsSeenThisSession();

        Set<StepId> completed = persistence.loadCompletedSteps();
        this.noPersistedProgress = completed.isEmpty();
        this.completedSteps.addAll(completed);

        this.skippedSteps.addAll(persistence.loadSkippedSteps());
        this.suppressedSteps.addAll(persistence.loadSuppressedSteps());

        List<StepId> recentManual = persistence.loadRecentManualSteps();
        this.recentManualSteps.addAll(
                recentManual.subList(0, Math.min(recentManual.size(), RECENT_MANUAL_LIMIT)));

        String savedVersion = persistence.loadGuideVersion();
        if (savedVersion != null && !savedVersion.equals(guide.getVersion())) {
            // The shipped guide differs from the one this progress was saved against. Completed
            // steps stay as saved. The logged-out preview loads read-only and logs this at DEBUG.
            // The real load at the next login logs it at INFO.
            if (persistence.isReadOnly()) {
                log.debug("Guide version differs from the saved one ({} → {}), completed steps kept",
                        savedVersion, guide.getVersion());
            } else {
                log.info("Guide version differs from the saved one ({} → {}), completed steps kept",
                        savedVersion, guide.getVersion());
            }
        }
        persistence.saveGuideVersion(guide.getVersion());

        birdhouseTracker.loadPersisted(persistence.loadBirdhouses());
        tearsOfGuthixTracker.loadPersisted(persistence.loadTearsOfGuthixReset());
        tearsOfGuthixTracker.loadPersistedAdventures(persistence.loadTearsOfGuthixAdventures());
        kingdomTracker.loadPersisted(persistence.loadKingdom());

        resolveAndRebuild();

        log.debug("Loaded {} completed, {} skipped steps", completedSteps.size(), skippedSteps.size());
    }

    /**
     * Forget everything held for the account that played last, so the load starts from an
     * account with nothing saved.
     *
     * <p>Without this, a second account logging in during the same client run would inherit
     * the first account's steps, player state and timers, and its next save would write them
     * into its own profile. A load that throws part-way still leaves part of the account, so
     * nothing may be saved after a failed load.
     *
     * <p>Every per-account field is cleared here except those kept across logins. Kept: the
     * sightings held for the session (they belong to the account logging in and are adopted
     * after the load), the Slayer baseline (dropped at logout instead), the snapshot and its
     * version (the load rebuilds the snapshot, and the panel needs the version to only move
     * forward), and everything built from the guide.
     */
    private void clearAccountState() {
        playerState = null;
        completedSteps.clear();
        skippedSteps.clear();
        suppressedSteps.clear();
        recentManualSteps.clear();
        noPersistedProgress = false;
        resetPending = false;
        currentStepId = null;
        autoCompletedSteps.clear();
        loginResolvePending = false;
        birdhouseTracker.clear();
        tearsOfGuthixTracker.clear();
        kingdomTracker.clear();
    }

    // ---------------------------------------------------------------
    // Event handlers — called from StepwisePlugin @Subscribe methods
    // ---------------------------------------------------------------

    public void onLogin(String playerName, AccountType accountType,
                        Map<String, EventApplier.SkillSnapshot> skills,
                        int questPoints, List<String> completedQuests,
                        List<String> startedQuests,
                        List<ItemStack> inventory,
                        Map<EquipmentSlot, ItemStack> equipment) {

        if (playerState == null) {
            playerState = new PlayerState(playerName, accountType);
        } else {
            playerState.setPlayerName(playerName);
            if (accountType != null) {
                playerState.setAccountType(accountType);
            }
        }

        adoptSightingsSeenThisSession();

        EventApplier.applyLoginState(playerState, skills, questPoints,
                completedQuests, startedQuests, inventory, equipment);

        afterEvent();

        // Milestone auto-latch: only on first login with no persisted progress
        // (see noPersistedProgress).
        if (noPersistedProgress && milestonesFile != null) {
            noPersistedProgress = false;
            performMilestoneLatch();
        }
        loginResolvePending = true;
    }

    /**
     * The steps a game-event flush latched while each was the active step, since the last
     * call, oldest first. Clears the list.
     */
    public List<GuideStep> takeAutoCompletedSteps() {
        List<GuideStep> steps = new ArrayList<>();
        for (StepId id : autoCompletedSteps) {
            steps.add(stepIndex.get(id));
        }
        autoCompletedSteps.clear();
        return steps;
    }

    /**
     * Apply milestone auto-latching: mark all steps up to the furthest cleared milestone's
     * anchor as completed, then rebuild the dashboard. Only called when there is no
     * existing persisted progress.
     */
    void performMilestoneLatch() {
        List<StepId> latched = MilestoneLatcher.computeLatchedSteps(milestonesFile, guide, playerState);
        if (latched.isEmpty()) {
            return;
        }
        completedSteps.addAll(latched);
        persistence.markDirty();
        resolveAndRebuild();
    }

    /**
     * Apply one game event to the player state and mark the state changed. Events before
     * the profile has loaded are dropped: the login scan re-reads everything they carried.
     */
    private void update(Consumer<PlayerState> change) {
        if (playerState == null) return;
        change.accept(playerState);
        afterEvent();
    }

    public void onStatChanged(String skillName, long xp, int level) {
        update(s -> EventApplier.applyStatChanged(s, skillName, xp, level));
    }

    public void onInventoryChanged(List<ItemStack> items) {
        update(s -> s.setInventory(items));
    }

    public void onBankChanged(List<ItemStack> items) {
        update(s -> {
            s.setBank(items);
            s.setBankLastSeen(Instant.now());
        });
    }

    /**
     * Replace the tracked tackle box contents. The game reports this container only when
     * the player opens the tackle box, so like the bank it is a snapshot of the last look.
     */
    public void onTackleBoxChanged(List<ItemStack> items) {
        update(s -> s.setTackleBox(items));
    }

    /** Replace the tracked seed vault contents, reported only when the player opens it. */
    public void onSeedVaultChanged(List<ItemStack> items) {
        update(s -> s.setSeedVault(items));
    }

    /** Replace one boat's cargo hold contents, reported only while the player looks in it. */
    public void onCargoHoldChanged(int slot, List<ItemStack> items) {
        update(s -> s.setCargoHold(slot, items));
    }

    public void onSeedBoxChanged(List<ItemStack> items) {
        update(s -> EventApplier.applySeedBoxChanged(s, items));
    }

    /** Records seeds the player has put in their seed box. */
    public void onSeedBoxStored(ItemStack stored) {
        update(s -> EventApplier.applySeedBoxStored(s, stored));
    }

    /**
     * Records seeds leaving the seed box, emptied into the bag or planted out of it.
     *
     * @param itemName as the game named it; a name the box is not holding is ignored
     * @param quantity how many left, as a positive number
     */
    public void onSeedBoxTaken(String itemName, int quantity) {
        update(s -> EventApplier.applySeedBoxTaken(s, itemName, quantity));
    }

    /**
     * Updates the tracked rune pouch contents (rune item ID → quantity), read from the
     * pouch varbits. Runes stored in a pouch are otherwise invisible to {@code hasItem}
     * checks because they leave the inventory item array.
     */
    public void onRunePouchChanged(Map<Integer, Integer> contents) {
        update(s -> s.setRunePouch(contents));
    }

    /** Called when items leave the inventory while a deposit box is open. */
    public void onDepositInferred(List<ItemStack> depositedItems) {
        update(s -> EventApplier.applyDepositInference(s, depositedItems));
    }

    public void onEquipmentChanged(Map<EquipmentSlot, ItemStack> equipment) {
        update(s -> EventApplier.replaceEquipment(s, equipment));
    }

    public void onQuestCompleted(String questName, int totalQuestPoints) {
        update(s -> {
            s.getQuests().put(questName, QuestStatus.COMPLETED);
            s.setQuestPoints(totalQuestPoints);
        });
        observeKingdomOnQuest(questName);
    }

    public void onQuestProgress(String questName, String questState, Integer newValue) {
        update(s -> EventApplier.applyQuestProgress(s, questName, questState, newValue));
        observeKingdomOnQuest(questName);
    }

    /** Set a kill count to the absolute value a chat message states. */
    public void onKillCount(String boss, long count) {
        update(s -> s.getKillCounts().put(boss, count));
    }

    /** Count one NPC kill detected via ActorDeath. */
    public void onNpcKill(String npcName) {
        update(s -> s.getKillCounts().merge(npcName, 1L, Long::sum));
    }

    public void onCollectionLog(String itemName) {
        update(s -> s.getCollectionLog().add(itemName));
    }

    public void onDiaryCompleted(String area, String difficulty) {
        update(s -> EventApplier.applyDiaryCompleted(s, area, difficulty));
    }

    public void onRegionChanged(int newRegionId) {
        update(s -> s.setRegionId(newRegionId));
        observeKingdomVisit(newRegionId);
    }

    /**
     * Records the bank interface opening or closing, which shows or hides the current step's
     * route-assumption reminders. Nothing is persisted, so only the snapshot is marked stale.
     */
    public void onBankOpenChanged(boolean open) {
        if (playerState == null || playerState.isBankOpen() == open) return;
        playerState.setBankOpen(open);
        resolveDirty = true;
    }

    /**
     * Record a built object seen in the loaded scene, for {@code objectPresent} conditions.
     *
     * <p>Called for every scene-object spawn, thousands per region load. The first check is
     * an allocation-free lookup that rejects almost every spawn; the second dedupes repeat
     * sightings such as house furniture re-spawning on every visit.
     *
     * <p>Reachable before the profile is loaded; see {@link #objectsSeenThisSession}.
     *
     * @param objectId scene object id, from any of the four object-spawn event types
     */
    public void onObjectSpawned(int objectId) {
        if (!watchedObjects.contains(objectId)) {
            return;
        }
        if (!objectsSeenThisSession.add(objectId)) {
            return;
        }
        if (playerState != null && playerState.getObservedObjects().add(objectId)) {
            afterEvent();
        }
    }

    /**
     * Record that the player clicked a tracked menu option on an NPC the guide asks about.
     *
     * <p>The game keeps no record of a conversation, so this is the only evidence an
     * {@code npcInteraction} step has. Reachable before the profile is loaded, like
     * {@link #onObjectSpawned}.
     *
     * @param npcName the NPC as the game names it
     * @param action  the menu option clicked
     */
    public void onNpcInteraction(String npcName, String action) {
        if (!watchedNpcs.watches(npcName)) {
            return;
        }
        String key = NpcInteractions.record(npcName, action);
        if (!npcInteractionsSeenThisSession.add(key)) {
            return;
        }
        if (playerState != null && playerState.getObservedNpcInteractions().add(key)) {
            afterEvent();
        }
    }

    /**
     * Fold this session's sightings (objects built, NPCs spoken to) into the current
     * {@link #playerState}. Called wherever the state is created or replaced. Idempotent:
     * both the session sets and the state's sets are grow-only.
     */
    private void adoptSightingsSeenThisSession() {
        if (playerState == null) {
            return;
        }
        if (!objectsSeenThisSession.isEmpty()) {
            playerState.getObservedObjects().addAll(objectsSeenThisSession);
        }
        if (!npcInteractionsSeenThisSession.isEmpty()) {
            playerState.getObservedNpcInteractions().addAll(npcInteractionsSeenThisSession);
        }
    }

    /**
     * Drop the sightings held for the session that is ending, at a logout or a world hop.
     *
     * <p>The engine outlives sessions, so without this the next login, possibly on another
     * account, would adopt the previous player's sightings into its grow-only profile sets.
     *
     * <p>Nothing is lost: once a session is established every sighting is written straight
     * into {@link #playerState} and saved with it.
     */
    public void forgetSessionSightings() {
        objectsSeenThisSession.clear();
        npcInteractionsSeenThisSession.clear();
    }

    /**
     * Fold one tick's Slayer task var sample in, crediting a master when a task is killed out.
     *
     * <p>Sampled per tick rather than on var changes because the decision needs the remaining
     * count and the streak read together; see {@link SlayerTaskTracker}.
     *
     * <p>Persists a completion immediately. Unlike a varbit-backed fact, nothing can re-read
     * it, so a crash before the next periodic save would lose it.
     */
    public void onSlayerTaskSample(int count, int master, int streak, int wildernessStreak) {
        if (playerState == null) return;

        // `noSlayerTask` reads only whether a task is in hand, so only the zero crossing
        // calls afterEvent. Calling it per kill would mark the profile dirty on every kill.
        //
        // A negative reading (varp never set) is kept as-is: it equals the "not sampled yet"
        // sentinel.
        int previous = playerState.getSlayerTaskCount();
        if (previous != count) {
            playerState.setSlayerTaskCount(count);
            if ((previous == 0) != (count == 0)) {
                afterEvent();
            }
        }

        Integer finished = slayerTasks.observe(count, master, streak, wildernessStreak);
        if (finished == null) return;
        int total = playerState.getSlayerTasksCompleted().merge(finished, 1, Integer::sum);
        log.debug("Slayer task completed for master {} (total {})", finished, total);
        // afterEvent before the save: afterEvent marks the profile dirty and the save clears it.
        afterEvent();
        persistence.forceSave(playerState, completedSteps, skippedSteps, suppressedSteps);
    }

    /** Drop the Slayer baseline at logout, so the next account's first sample re-seeds it. */
    public void onSlayerTrackingReset() {
        slayerTasks.reset();
        if (playerState != null) {
            // The count belongs to the account that just left. Keeping it would answer the
            // next login's first resolve from a stale value before any tick has sampled.
            playerState.setSlayerTaskCount(PlayerState.SLAYER_TASK_COUNT_UNREAD);
        }
    }

    /**
     * Track an arbitrary varbit value change for VarbitValue completion conditions
     * (e.g. individual achievement diary tasks).
     */
    public void onVarbitValueChanged(int varbitId, int value) {
        if (playerState == null) return;
        Integer prev = playerState.getVarbits().get(varbitId);
        if (prev != null && prev == value) return;
        playerState.getVarbits().put(varbitId, value);
        observeTearsOfGuthixVarbit(varbitId, value);
        observeKingdomVarbit(varbitId);
        observeBoatOwnershipVarbit(varbitId, value);
        afterEvent();
    }

    /**
     * Drops a cargo hold snapshot when its boat stops being the player's. The hold goes with
     * the boat, and the next boat in that slot starts empty.
     */
    private void observeBoatOwnershipVarbit(int varbitId, int value) {
        int slot = BoatVarbits.slotOfOwnedVarbit(varbitId);
        if (slot >= 0 && value == 0) {
            playerState.clearCargoHold(slot);
        }
    }

    /**
     * Track a VarPlayer value change for VarpBitValue completion conditions
     * (e.g. individual achievement diary tasks stored as bit fields).
     */
    public void onVarpValueChanged(int varpId, int value) {
        if (playerState == null) return;
        Integer prev = playerState.getVarps().get(varpId);
        if (prev != null && prev == value) return;
        playerState.getVarps().put(varpId, value);
        afterEvent();
    }

    // ---------------------------------------------------------------
    // Bird house varbit events
    // ---------------------------------------------------------------

    /**
     * Fold the current bird house varp values into the tracker; see
     * {@link BirdhouseTracker#update}. Persists on any change.
     *
     * @return true if any house changed, so the plugin should refresh the InfoBoxes
     */
    public boolean updateBirdhouses(Map<BirdhouseTracker.BirdhouseSpace, Integer> varps,
                                    boolean inRegion, long nowEpochSec) {
        boolean changed = birdhouseTracker.update(varps, inRegion, nowEpochSec);
        if (changed) {
            persistence.saveBirdhouses(birdhouseTracker.exportForPersist());
        }
        return changed;
    }

    /**
     * Start the weekly Tears of Guthix clock when the in-cave tick bar starts running.
     * See {@link TearsOfGuthixTracker} for why the visit is stamped rather than read.
     */
    private void observeTearsOfGuthixVarbit(int varbitId, int value) {
        if (varbitId != TearsOfGuthixTracker.IN_CAVE_TICKS_VARBIT) {
            return;
        }
        // Entering the cave starts both the weekly clock and the adventuring gate, so the
        // tracker arms them together.
        if (tearsOfGuthixTracker.observeVisit(value, Instant.now().getEpochSecond(),
                playerState != null ? playerState.getTotalXp() : -1L,
                playerState != null ? playerState.getQuestPoints() : -1)) {
            saveTearsOfGuthix();
        }
    }

    private void saveTearsOfGuthix() {
        persistence.saveTearsOfGuthixReset(tearsOfGuthixTracker.exportForPersist());
        persistence.saveTearsOfGuthixAdventures(
                tearsOfGuthixTracker.exportAdventuresForPersist());
    }

    /** Fold a live change to one of the kingdom's varbits into {@link #kingdomTracker}. */
    private void observeKingdomVarbit(int varbitId) {
        for (int id : KingdomTracker.VARBIT_IDS) {
            if (id == varbitId) {
                observeKingdom();
                return;
            }
        }
    }

    /**
     * Primes {@link #kingdomTracker} from the login scan, which writes the kingdom varbits
     * straight into playerState and so bypasses {@link #onVarbitValueChanged}. Safe to call
     * every login: an unchanged observation is a no-op. A login on the islands needs no visit
     * here, because the region tracker announces the first region of every session.
     */
    public void primeKingdomFromLogin() {
        observeKingdom();
    }

    /**
     * Finishing Throne of Miscellania changes no kingdom varbit, so nothing else would observe
     * the kingdom before the next login, and the Kingdom row would stay hidden until then.
     */
    private void observeKingdomOnQuest(String questName) {
        if (KingdomTracker.QUEST_NAME.equals(questName)) {
            observeKingdom();
        }
    }

    private void observeKingdom() {
        if (!KingdomTracker.questCompleted(playerState)) {
            return;
        }
        Map<Integer, Integer> varbits = playerState.getVarbits();
        kingdomTracker.observeAllocation(varbits);
        if (kingdomTracker.observe(
                varbits.getOrDefault(KingdomTracker.APPROVAL_VARBIT, 0),
                varbits.getOrDefault(KingdomTracker.COFFER_VARBIT, 0),
                varbits.getOrDefault(KingdomTracker.POOL_VARBIT, 0),
                Instant.now().getEpochSecond())) {
            persistence.saveKingdom(kingdomTracker.exportForPersist());
        }
    }

    /** Standing on Miscellania or Etceteria counts as a visit, which holds off the 30-day pause. */
    private void observeKingdomVisit(int regionId) {
        if (!KingdomTracker.isKingdomRegion(regionId)
                || !KingdomTracker.questCompleted(playerState)) {
            return;
        }
        if (kingdomTracker.markVisit(Instant.now().getEpochSecond())) {
            persistence.saveKingdom(kingdomTracker.exportForPersist());
        }
    }

    /**
     * Offer a game chat line to the Tears of Guthix clock, for Juna's eligibility notice.
     *
     * <p>Offered every game message, so the cheap match runs first.
     */
    public void onTearsOfGuthixMessage(String message) {
        if (!TearsOfGuthixTracker.isEligibilityMessage(message)) {
            return;
        }
        if (tearsOfGuthixTracker.markEligible(Instant.now().getEpochSecond())) {
            saveTearsOfGuthix();
            afterEvent();
        }
    }

    /**
     * Offer a line of NPC dialogue, or a message box, to the Tears of Guthix clock.
     *
     * <p>Juna's refusal arms the adventuring gate (100,000 experience or a quest point since
     * the last visit) from the current totals unless a cave entry already holds an unmet
     * baseline, and any line of hers that states the wait sets the clock.
     *
     * @param dialogue one line, its widget tags already stripped
     * @return true if anything moved
     */
    public boolean onTearsOfGuthixDialogue(String dialogue) {
        if (playerState == null || !TearsOfGuthixTracker.questCompleted(playerState)) {
            return false;
        }
        TearsOfGuthixTracker.JunaLine line = TearsOfGuthixTracker.parseJunaLine(dialogue);
        if (line == null) {
            if (TearsOfGuthixTracker.isJunaSpeaking(dialogue)) {
                log.debug("Juna said something unread: {}", dialogue);
            }
            return false;
        }
        if (!tearsOfGuthixTracker.applyJunaLine(line, Instant.now().getEpochSecond(),
                playerState.getTotalXp(), playerState.getQuestPoints())) {
            return false;
        }
        saveTearsOfGuthix();
        afterEvent();
        log.debug("Tears of Guthix clock set from Juna: {} day(s), adventures needed: {}",
                line.getDaysRemaining(), line.isWantsAdventures());
        return true;
    }

    /**
     * Set the Tears of Guthix clock from the journal page the player has open.
     *
     * <p>An unreadable page is logged, not guessed at: the sentence is composed by the
     * server.
     *
     * @param journalText the page's text, its widget tags already stripped
     * @return true if the clock moved
     */
    public boolean onTearsOfGuthixJournal(String journalText) {
        // Before the quest is finished the page is a walkthrough with no weekly visit to clock.
        if (!TearsOfGuthixTracker.questCompleted(playerState)) {
            return false;
        }
        int days = TearsOfGuthixTracker.parseJournalDays(journalText);
        if (days == TearsOfGuthixTracker.JOURNAL_DAYS_UNREAD) {
            log.debug("Tears of Guthix journal page states no readable wait: {}", journalText);
            return false;
        }
        if (!tearsOfGuthixTracker.setWaitDays(days, Instant.now().getEpochSecond())) {
            return false;
        }
        saveTearsOfGuthix();
        afterEvent();
        log.debug("Tears of Guthix clock set from the quest journal: {} day(s)", days);
        return true;
    }

    /**
     * Refine one bird house's timer from a "Seed level is: X/10" check dialog.
     */
    public void applyBirdhouseSeedProbe(BirdhouseTracker.BirdhouseSpace space,
                                        int seedLevel, long nowEpochSec) {
        birdhouseTracker.applySeedProbe(space, seedLevel, nowEpochSec);
        persistence.saveBirdhouses(birdhouseTracker.exportForPersist());
    }

    // ---------------------------------------------------------------
    // Manual step overrides
    // ---------------------------------------------------------------

    /**
     * Mark a step as done. Safe to call from any thread — if called from the EDT,
     * the actual mutation is dispatched to the client thread.
     */
    public void markStepDone(StepId stepId) {
        Runnable action = () -> {
            flushBeforeClick();
            completedSteps.add(stepId);
            skippedSteps.remove(stepId);
            suppressedSteps.remove(stepId);
            resetKillCountsForStep(stepId);
            recordManualAction(stepId);
            persistence.markDirty();
            resolveAndRebuild();
            log.debug("Step {} marked done", stepId.getValue());
        };
        runOnClientThread(action);
    }

    /**
     * Skip a step. Safe to call from any thread.
     */
    public void skipStep(StepId stepId) {
        Runnable action = () -> {
            flushBeforeClick();
            skippedSteps.add(stepId);
            completedSteps.remove(stepId);
            recordManualAction(stepId);
            persistence.markDirty();
            resolveAndRebuild();
            log.debug("Step {} skipped", stepId.getValue());
        };
        runOnClientThread(action);
    }

    /**
     * Skip several steps as one action. Safe to call from any thread.
     *
     * <p>Batched {@link #skipStep}: skipping every step of a run of guide entries resolves and
     * redraws once, not once per step. Unlike {@link #skipStep} it records nothing in
     * {@link #recentManualSteps}: the batch moves the active step past the skipped steps, so
     * Recent's rows behind the active step already offer Undo on its tail, and recording
     * each step would push the player's own clicks out of the bounded list.
     */
    public void skipSteps(Collection<StepId> stepIds) {
        if (stepIds == null || stepIds.isEmpty()) {
            return;
        }
        List<StepId> batch = new ArrayList<>(stepIds);
        Runnable action = () -> {
            flushBeforeClick();
            for (StepId stepId : batch) {
                skippedSteps.add(stepId);
                completedSteps.remove(stepId);
            }
            persistence.markDirty();
            resolveAndRebuild();
            log.debug("{} steps skipped: {}", batch.size(), batch);
        };
        runOnClientThread(action);
    }

    /**
     * Undo a step completion. Safe to call from any thread.
     */
    public void undoStep(StepId stepId) {
        Runnable action = () -> {
            flushBeforeClick();
            completedSteps.remove(stepId);
            skippedSteps.remove(stepId);
            suppressedSteps.add(stepId);
            if (recentManualSteps.remove(stepId)) {
                persistence.saveRecentManualSteps(recentManualSteps);
            }
            persistence.markDirty();
            resolveAndRebuild();
            log.debug("Step {} undone, still suppressed: {}", stepId.getValue(),
                    suppressedSteps.contains(stepId));
        };
        runOnClientThread(action);
    }

    /**
     * Resolve the game events still waiting for the tick flush before a click changes the
     * guide state. Otherwise the click's resolve, which never announces, would latch a step
     * the game finished and its notification would be lost.
     */
    private void flushBeforeClick() {
        flushIfDirty();
    }

    /**
     * Put a Mark Done or Skip click at the head of {@link #recentManualSteps} and save the list
     * at once, so a crash before the next debounced save still leaves its Undo. Runs on the
     * client thread with the action it records.
     */
    private void recordManualAction(StepId stepId) {
        recentManualSteps.remove(stepId);
        recentManualSteps.add(0, stepId);
        while (recentManualSteps.size() > RECENT_MANUAL_LIMIT) {
            recentManualSteps.remove(recentManualSteps.size() - 1);
        }
        persistence.saveRecentManualSteps(recentManualSteps);
    }

    /** The latest Mark Done and Skip clicks, newest first. */
    List<StepId> getRecentManualSteps() {
        return Collections.unmodifiableList(recentManualSteps);
    }

    // ---------------------------------------------------------------
    // Guide progress reset
    // ---------------------------------------------------------------

    /**
     * Wipe all guide progression (completed, skipped, suppressed steps). Creates a fresh
     * PlayerState carrying over only what live game state cannot re-supply, force-saves it,
     * and sets {@link #resetPending} so StepwisePlugin re-runs the full login scan on the next
     * game tick.
     *
     * <p>Safe to call from any thread.
     */
    public void resetGuideProgress() {
        Runnable action = () -> {
            completedSteps.clear();
            skippedSteps.clear();
            suppressedSteps.clear();
            recentManualSteps.clear();
            persistence.saveRecentManualSteps(recentManualSteps);

            PlayerState old = playerState;
            playerState = old == null ? new PlayerState(null, null)
                    : new PlayerState(old.getPlayerName(), old.getAccountType());
            if (old != null) {
                // Preserve bank, tackle box, seed vault, seed box and cargo holds: each is
                // reported only when the player opens it. Losing the seed box would make
                // carried seeds read as missing.
                if (old.getBank() != null) {
                    playerState.setBank(old.getBank());
                }
                playerState.setBankLastSeen(old.getBankLastSeen());
                playerState.setTackleBox(old.getTackleBox());
                playerState.setSeedVault(old.getSeedVault());
                playerState.setSeedBox(old.getSeedBox());
                old.getCargoHolds().forEach(playerState::setCargoHold);
                // Finished Slayer tasks, object sightings and NPC conversations have no game
                // var to re-read; these sets are their only record. A quest dialogue cannot be
                // re-opened once the quest moves past it.
                playerState.getSlayerTasksCompleted().putAll(old.getSlayerTasksCompleted());
                playerState.getObservedObjects().addAll(old.getObservedObjects());
                playerState.getObservedNpcInteractions().addAll(old.getObservedNpcInteractions());
            }

            persistence.forceSave(playerState, completedSteps, skippedSteps, suppressedSteps);

            lastSnapshot = null;
            snapshotVersion++;

            resetPending = true;

            log.info("Guide progress reset (bank preserved: {} items, tackle box: {} items, "
                            + "seed vault: {} items, seed box: {} items, cargo holds: {}, "
                            + "observed objects: {})",
                    playerState.getBank().size(), playerState.getTackleBox().size(),
                    playerState.getSeedVault().size(), playerState.getSeedBox().size(),
                    playerState.getCargoHolds().size(), playerState.getObservedObjects().size());
        };
        runOnClientThread(action);
    }

    /**
     * Clear the bank snapshot from PlayerState, independently of guide progress. Safe to
     * call from any thread.
     */
    public void clearBankSnapshot() {
        // Called from a panel button on the EDT. PlayerState readings are plain fields that
        // rely on client-thread-only access, so the mutation must hop threads.
        runOnClientThread(() -> {
            if (playerState == null) {
                return;
            }
            flushBeforeClick();
            playerState.setBank(Collections.emptyList());
            playerState.setBankLastSeen(null);
            persistence.markDirty();
            resolveAndRebuild();
            log.info("Bank snapshot cleared");
        });
    }

    /**
     * Clear all bird house timers and their persisted state, independently of
     * {@link #resetGuideProgress()}. Houses in view re-discover at a fresh 50 minutes.
     * Safe to call from any thread.
     */
    public void resetBirdhouseTimers() {
        runOnClientThread(() -> {
            birdhouseTracker.clear();
            persistence.saveBirdhouses(Collections.emptyList());
            markTimersDirty();
            log.info("Bird house timers reset");
        });
    }

    /**
     * Bump the snapshot version so the side panel refreshes on the next tick. Bird house
     * timers change without a guide event, which would not move
     * {@link #snapshotVersion}. Client-thread only.
     */
    public void markTimersDirty() {
        snapshotVersion++;
    }

    public boolean isResetPending() {
        return resetPending;
    }

    /** Consume the reset-pending flag after StepwisePlugin has re-scanned. */
    public void consumeResetPending() {
        this.resetPending = false;
    }

    // ---------------------------------------------------------------
    // Periodic save (call from GameTick)
    // ---------------------------------------------------------------

    public void tickSave() {
        persistence.saveIfNeeded(playerState, completedSteps, skippedSteps, suppressedSteps);
    }

    public void forceSave() {
        persistence.forceSave(playerState, completedSteps, skippedSteps, suppressedSteps);
        saveSessionSummary();
    }

    /**
     * Update the session summary in non-profile config, so the welcome screen can show
     * "last session" info before the next login.
     *
     * <p>Skipped while the player's name is unknown, which a login that gave up waiting for
     * it can leave. The welcome page heads the summary with the name, so the previous summary
     * stays rather than one with no name.
     */
    public void saveSessionSummary() {
        if (lastSnapshot == null || lastSnapshot.getGuidePosition() == null) {
            return;
        }
        String playerName = playerState != null ? playerState.getPlayerName() : null;
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        GuidePosition pos = lastSnapshot.getGuidePosition();
        persistence.saveSessionSummary(
                playerName,
                pos.getStepIndex(),
                pos.getTotalSteps(),
                pos.getChapter(),
                completedSteps.size(),
                guide.minutesDone(completedSteps),
                guide.getTotalMinutes());
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    /**
     * Run an action on the client thread via {@link #clientThreadExecutor}, or inline when
     * none is set.
     */
    private void runOnClientThread(Runnable action) {
        if (clientThreadExecutor != null) {
            clientThreadExecutor.accept(action);
        } else {
            action.run();
        }
    }

    private void afterEvent() {
        persistence.markDirty();
        resolveDirty = true;
    }

    /**
     * Resolve once if any event dirtied state since the last flush. StepwisePlugin calls it
     * each game tick and once the login scan finishes. Mark Done, Skip, Undo and clearing the
     * bank snapshot call it before they change the state.
     */
    public void flushIfDirty() {
        if (resolveDirty) {
            resolveDirty = false;
            // The flush after a login scan (login, world hop or reset) checks off steps the
            // game already shows done. That is not news, so it does not notify.
            boolean announce = !loginResolvePending;
            loginResolvePending = false;
            resolveAndRebuild(announce);
        }
    }

    /**
     * Trigger a resolve-and-rebuild pass after external state changes
     * (e.g. bulk var population at login). Marks persistence dirty.
     */
    public void triggerResolve() {
        afterEvent();
    }

    /**
     * Returns true if every prerequisite of the step is completed or skipped. A step at the
     * reachable frontier whose transient condition is met reflects the player doing it, not
     * coincidental game state.
     *
     * <p>Reads the persisted completed/skipped sets, unlike the resolver's frontier check,
     * which uses the in-progress resolve map. The resolveAndRebuild fixpoint loop reconciles
     * the two by re-resolving after each latch iteration.
     */
    private boolean prerequisitesComplete(GuideStep step) {
        if (step.getPrerequisites() == null || step.getPrerequisites().isEmpty()) {
            return true;
        }
        for (StepId prereq : step.getPrerequisites()) {
            if (!completedSteps.contains(prereq) && !skippedSteps.contains(prereq)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if a downstream consumer whose completion proves this WHOLE step was done is
     * already complete. Only the edges {@link #frontierServesEdges} yields qualify. An arm's edge
     * under an {@code all} composite proves only that arm, and
     * {@link ResolutionEngine#evaluateCondition} already credits it while still demanding the
     * other arms; counting it here would latch the whole step off one arm's consumer.
     */
    private boolean wholeStepServedComplete(GuideStep step) {
        for (StepId served : frontierServesEdges(step.getCompletion())) {
            if (completedSteps.contains(served)) return true;
        }
        return false;
    }

    /**
     * Returns true if the step is a pure travel step: locationReached condition with no
     * servesSteps edges. These steps exist only for narrative flow ("go to Varrock") and
     * can be auto-completed when the player is already past the destination.
     */
    private static boolean isPureTravelStep(GuideStep step) {
        CompletionCondition condition = step.getCompletion();
        if (!(condition instanceof CompletionCondition.LocationReached)) {
            return false;
        }
        List<StepId> serves = condition.getServesSteps();
        return serves == null || serves.isEmpty();
    }

    /**
     * Returns true if any step that lists this step as a prerequisite is completed or has
     * its condition met by the current game state, proving the player is past this step.
     */
    private boolean anyDependentConditionMet(StepId stepId) {
        Set<StepId> dependents = prerequisiteOf.get(stepId);
        if (dependents == null || playerState == null) return false;
        for (StepId dep : dependents) {
            if (completedSteps.contains(dep)) return true;
            GuideStep depStep = stepIndex.get(dep);
            if (depStep != null
                    && ResolutionEngine.evaluateCondition(depStep.getCompletion(), playerState)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strict variant of {@link #anyDependentConditionMet}: true only if a dependent is in
     * {@code completedSteps}. Used for softLatch steps, so coincidental game state (holding a
     * common item the next step needs) cannot latch them.
     */
    private boolean anyDependentCompleted(StepId stepId) {
        Set<StepId> dependents = prerequisiteOf.get(stepId);
        if (dependents == null) return false;
        for (StepId dep : dependents) {
            if (completedSteps.contains(dep)) return true;
        }
        return false;
    }

    /**
     * Whether this step would auto-latch right now, ignoring its own suppression. The
     * auto-latch loop and the check that lifts an undone step's suppression both use it, so
     * they cannot disagree.
     *
     * <p>The loop latches through serves-edge catch-up, pure-travel and soft-latch pathways
     * as well as the direct condition. A suppression clear that checked only the direct
     * condition would release a step the loop then re-latches, reverting the user's undo.
     *
     * @param id     the step to test
     * @param status the step's status from the current (suppression-aware) resolve pass; passed
     *               in to avoid an O(n) lookup per call inside the latch loop
     */
    private boolean isAutoLatchable(StepId id, StepStatus status) {
        if (completedSteps.contains(id) || skippedSteps.contains(id)) {
            return false;
        }
        GuideStep step = stepIndex.get(id);
        if (step == null) {
            return false;
        }
        // Direct pathway: the resolve pass already considers it complete, or (for a suppressed
        // step, whose status is forced to ACTIVE/PENDING) its raw condition is met now. The
        // second check matters only to the suppression clear; for a non-suppressed step the
        // first dominates it. It mirrors the resolve pass's trust gate: a met transient
        // condition counts only at the prerequisite frontier.
        if (status == StepStatus.COMPLETED) {
            return true;
        }
        if ((ResolutionEngine.isMonotonic(step.getCompletion()) || prerequisitesComplete(step))
                && ResolutionEngine.evaluateCondition(
                        step.getCompletion(), playerState, completedSteps)) {
            return true;
        }
        // Durable-evidence pathway, mirroring the resolve pass's login/reset pre-scan. Needed
        // because isMonotonic is mode-blind and strict: an `any` composite with one
        // durable arm beside a regressing one reads non-monotonic above, yet the pre-scan latches
        // it through the durable arm. Without this the suppression clear would revert an undo of
        // such a step. Adds no latch for a non-suppressed step.
        if (ResolutionEngine.monotonicallyMet(step.getCompletion(), playerState)) {
            return true;
        }
        // Backward-DAG/serves closure, checked BEFORE the ACTIVE gate because ResolutionEngine's
        // closure walk is not frontier- or ACTIVE-gated: a step whose consumers are complete
        // resolves COMPLETED once un-suppressed, however far ahead it sits. Uses the resolver's
        // own composite-aware predicate so the two never diverge.
        if (!ResolutionEngine.unmetProvesIncomplete(step.getCompletion())
                && ResolutionEngine.allServedStepsComplete(step.getCompletion(), completedSteps)) {
            return true;
        }
        // Remaining catch-up pathways fire only at the frontier. BACKGROUND counts: a background
        // step is reachable and merely declines the pointer.
        if (status != StepStatus.ACTIVE && status != StepStatus.BACKGROUND) {
            return false;
        }
        // Frontier prep-step catch-up: a step latches once a consumer that proves the whole step
        // is complete (see frontierServesEdges). Looser than the closure check above, and safe
        // because it fires only at the frontier.
        if (wholeStepServedComplete(step)) {
            return true;
        }
        if (isPureTravelStep(step) && anyDependentConditionMet(id)) {
            return true;
        }
        return step.isSoftLatch() && anyDependentCompleted(id);
    }

    private void resetKillCountsForStep(StepId stepId) {
        if (playerState == null) return;
        GuideStep step = stepIndex.get(stepId);
        if (step == null || step.getCompletion() == null) return;
        resetKillCountsInCondition(step.getCompletion());
    }

    private void resetKillCountsInCondition(CompletionCondition condition) {
        if (condition instanceof CompletionCondition.KillCount) {
            String target = ((CompletionCondition.KillCount) condition).getTarget();
            playerState.getKillCounts().remove(target);
        } else if (condition instanceof CompletionCondition.Composite) {
            for (CompletionCondition sub : ((CompletionCondition.Composite) condition).getConditions()) {
                resetKillCountsInCondition(sub);
            }
        }
    }

    /**
     * Resolve, auto-latch to a fixpoint, and rebuild the snapshot. Never announces a
     * completion: only {@link #flushIfDirty} does.
     */
    private void resolveAndRebuild() {
        resolveAndRebuild(false);
    }

    /**
     * As {@link #resolveAndRebuild()}, recording the active step for
     * {@link #takeAutoCompletedSteps} when {@code announce} is set and that step latches.
     */
    private void resolveAndRebuild(boolean announce) {
        if (playerState == null) return;
        StepId activeBefore = currentStepId;
        boolean activeLatched = false;

        List<ResolvedStep> resolved = ResolutionEngine.resolveSteps(
                playerState, guide, completedSteps, skippedSteps, suppressedSteps);

        // Release a user's undo suppression only once no pathway would auto-latch the step
        // (see isAutoLatchable). A later genuine re-reach can then auto-complete it again.
        Map<StepId, StepStatus> statusByStep = new HashMap<>();
        for (ResolvedStep rs : resolved) {
            statusByStep.put(rs.getStepId(), rs.getStatus());
        }
        suppressedSteps.removeIf(id ->
                !isAutoLatchable(id, statusByStep.getOrDefault(id, StepStatus.PENDING)));

        // Auto-latch every latchable, unsuppressed step (pathways in isAutoLatchable). Pure
        // travel steps latch once a dependent's condition is met, which covers a player in the
        // right city but a different map chunk from the one locationReached names.
        //
        // Fixpoint loop: a softLatch step waits for its dependent to be in completedSteps, and
        // that dependent may be latched in this same pass. Re-resolving between iterations lets
        // the cascade converge in one call. Each step latches at most once, so the step count
        // caps the loop. directLatched collects, across iterations, the steps latched on their
        // own directly-met condition, for the retract check below.
        Set<StepId> directLatched = new HashSet<>();
        int maxIterations = guide.allSteps().size();
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean latched = false;
            for (ResolvedStep rs : resolved) {
                StepId id = rs.getStepId();
                if (suppressedSteps.contains(id)) {
                    continue;
                }
                if (isAutoLatchable(id, rs.getStatus())) {
                    completedSteps.add(id);
                    activeLatched |= id.equals(activeBefore);
                    // Record latch provenance for the retract check. A catch-up latch (serves
                    // edge, closure, pure-travel, softLatch) legitimately has an unmet direct
                    // condition and must never be retracted. Evaluated before
                    // resetKillCountsForStep, which would un-meet a killCount sub-condition.
                    GuideStep step = stepIndex.get(id);
                    if (step != null && ResolutionEngine.evaluateCondition(
                            step.getCompletion(), playerState, completedSteps)) {
                        directLatched.add(id);
                    }
                    latched = true;
                    log.debug("Auto-latched step {} as completed", id.getValue());
                    resetKillCountsForStep(id);
                }
            }

            if (!latched) {
                break;
            }
            resolved = ResolutionEngine.resolveSteps(
                    playerState, guide, completedSteps, skippedSteps, suppressedSteps);
        }

        // Retract the step immediately before active if it was direct-latched this cycle and
        // its condition is no longer met: a bank withdrawal step can latch briefly before the
        // items leave the inventory again. Manual, persisted and catch-up latches are never
        // retracted; retracting a catch-up latch would oscillate with its pathway.
        if (!directLatched.isEmpty()) {
            StepId lastCompleted = null;
            for (ResolvedStep rs : resolved) {
                if (rs.getStatus() == StepStatus.ACTIVE) break;
                if (rs.getStatus() == StepStatus.COMPLETED) lastCompleted = rs.getStepId();
            }
            if (lastCompleted != null && directLatched.contains(lastCompleted)) {
                GuideStep lastStep = stepIndex.get(lastCompleted);
                if (lastStep != null && hasInventoryCondition(lastStep.getCompletion())
                        && !ResolutionEngine.evaluateCondition(
                                lastStep.getCompletion(), playerState, completedSteps)
                        && !ResolutionEngine.allServedStepsComplete(
                                lastStep.getCompletion(), completedSteps)) {
                    completedSteps.remove(lastCompleted);
                    resolved = ResolutionEngine.resolveSteps(
                            playerState, guide, completedSteps, skippedSteps, suppressedSteps);
                    log.debug("Retracted step {} (auto-latched but items left inventory)",
                            lastCompleted.getValue());
                }
            }
        }

        if (announce && activeLatched && completedSteps.contains(activeBefore)) {
            autoCompletedSteps.add(activeBefore);
        }

        StepId activeStepId = null;
        for (ResolvedStep rs : resolved) {
            if (rs.getStatus() == StepStatus.ACTIVE) {
                activeStepId = rs.getStepId();
                if (stepIndex.containsKey(activeStepId)) {
                    currentStepId = activeStepId;
                }
                break;
            }
        }

        DashboardSnapshot newSnapshot = SnapshotBuilder.buildSnapshot(
                playerState, guide, currentStepId, stepIndex, guide.stepContextMap(), resolved,
                recentManualSteps);

        // Bump the version only on a displayed change, so frequent var events don't make the
        // panel rebuild and flicker.
        if (!snapshotContentEquals(lastSnapshot, newSnapshot)) {
            snapshotVersion++;
        }
        lastSnapshot = newSnapshot;
    }

    /**
     * Compare two snapshots by display-relevant fields only.
     *
     * <p>Every drawn field that can move without the guide position moving is compared here,
     * so its part of the panel redraws.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean snapshotContentEquals(DashboardSnapshot a, DashboardSnapshot b) {
        if (a == null || b == null) {
            return a == b;
        }
        GuidePosition posA = a.getGuidePosition();
        GuidePosition posB = b.getGuidePosition();
        if ((posA == null) != (posB == null)) {
            return false;
        }
        if (posA != null && (!Objects.equals(posA.getStepId(), posB.getStepId())
                || posA.getStepIndex() != posB.getStepIndex())) {
            return false;
        }
        if (!currentStepEqual(a.getCurrentStep(), b.getCurrentStep())) {
            return false;
        }
        if (!detailedListEqual(a.getNextDetailed(), b.getNextDetailed())) {
            return false;
        }
        // Compared in full. Recent lists only the first two completions ahead of the active
        // step, so a step completing further down the Upcoming one-liners changes this list
        // and nothing else in the snapshot.
        if (!Objects.equals(a.getNextSteps(), b.getNextSteps())) {
            return false;
        }
        if (!detailedListEqual(a.getBackgroundSteps(), b.getBackgroundSteps())) {
            return false;
        }
        if (!Objects.equals(a.getRouteDeficit(), b.getRouteDeficit())) {
            return false;
        }
        if (!Objects.equals(a.getKingdomHardwood(), b.getKingdomHardwood())) {
            return false;
        }
        return detailedListEqual(a.getRecentCompleted(), b.getRecentCompleted());
    }

    private static boolean currentStepEqual(CurrentStepSummary a, CurrentStepSummary b) {
        if ((a == null) != (b == null)) {
            return false;
        }
        if (a == null) {
            return true;
        }
        // Items-needed entries are discrete display fields, so list equality catches an
        // ownership flip; without it ItemsNeededView never sees the change.
        // The assumption nudges come and go with the bank, which moves nothing else, so
        // without them opening the bank would not redraw.
        return criterionListEqual(a.getCriteria(), b.getCriteria())
                && Objects.equals(a.getItemsNeeded(), b.getItemsNeeded())
                && Objects.equals(a.getAssumptionNudges(), b.getAssumptionNudges());
    }

    private static boolean criterionListEqual(List<CompletionCriterion> a, List<CompletionCriterion> b) {
        if ((a == null) != (b == null)) {
            return false;
        }
        if (a == null) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            CompletionCriterion ca = a.get(i);
            CompletionCriterion cb = b.get(i);
            if (ca.isMet() != cb.isMet()) {
                return false;
            }
            if (!Objects.equals(ca.getDescription(), cb.getDescription())) {
                return false;
            }
            if (ca.getProgress() != null && cb.getProgress() != null) {
                if (ca.getProgress().getCurrent() != cb.getProgress().getCurrent()) {
                    return false;
                }
            } else if ((ca.getProgress() == null) != (cb.getProgress() == null)) {
                return false;
            }
        }
        return true;
    }

    private static boolean detailedListEqual(List<DetailedStep> a, List<DetailedStep> b) {
        if ((a == null) != (b == null)) {
            return false;
        }
        if (a == null) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Objects.equals(a.get(i).getStepId(), b.get(i).getStepId())
                    || a.get(i).isSkipped() != b.get(i).isSkipped()) {
                return false;
            }
            if (!criterionListEqual(a.get(i).getCriteria(), b.get(i).getCriteria())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the condition, or any composite sub-condition, is a {@code hasItem} or
     * {@code hasItemTotal} check with {@code location=inventory}. Identifies steps eligible
     * for same-cycle retraction when items leave the inventory.
     */
    static boolean hasInventoryCondition(CompletionCondition condition) {
        if (condition instanceof CompletionCondition.HasItem) {
            return ((CompletionCondition.HasItem) condition).getLocation() == ItemLocation.INVENTORY;
        }
        if (condition instanceof CompletionCondition.HasItemTotal) {
            return ((CompletionCondition.HasItemTotal) condition).getLocation() == ItemLocation.INVENTORY;
        }
        if (condition instanceof CompletionCondition.Composite) {
            for (CompletionCondition sub : ((CompletionCondition.Composite) condition).getConditions()) {
                if (hasInventoryCondition(sub)) return true;
            }
        }
        return false;
    }

    /**
     * The {@code servesSteps} targets whose completion proves the whole step was done: the
     * edge set the frontier catch-up reads.
     * <ul>
     *   <li>A plain condition contributes its own list.</li>
     *   <li>An {@code any} composite contributes its own list and recurses into every arm,
     *       because one arm completes the step.</li>
     *   <li>An {@code all} composite never contributes its arms' edges, because an arm's
     *       consumer proves only that arm ({@link ResolutionEngine#evaluateCondition} credits
     *       it per arm). It contributes its own list only when no arm carries an edge: that
     *       list joins every arm's consumers, so reading it beside edged arms would complete
     *       the whole step as soon as any one arm's consumer finished.</li>
     * </ul>
     * The {@code optional} flag is not consulted.
     *
     * <p>Not the set {@link ResolutionEngine#allServedStepsComplete} reads: that check fails an
     * {@code all} composite at its first edgeless arm, and reads the node's own list only after
     * every edged arm has passed.
     */
    static Set<StepId> frontierServesEdges(CompletionCondition condition) {
        Set<StepId> out = new HashSet<>();
        collectFrontierServesEdges(condition, out);
        return out;
    }

    private static void collectFrontierServesEdges(CompletionCondition condition, Set<StepId> out) {
        if (condition == null) return;
        if (condition instanceof CompletionCondition.Composite) {
            CompletionCondition.Composite composite = (CompletionCondition.Composite) condition;
            if (composite.getMode() == CompositeMode.ALL) {
                boolean anyArmEdged = false;
                for (CompletionCondition sub : composite.getConditions()) {
                    if (carriesAnyServesEdge(sub)) {
                        anyArmEdged = true;
                        break;
                    }
                }
                if (!anyArmEdged && composite.getServesSteps() != null) {
                    out.addAll(composite.getServesSteps());
                }
                return;
            }
            if (composite.getServesSteps() != null) {
                out.addAll(composite.getServesSteps());
            }
            for (CompletionCondition sub : composite.getConditions()) {
                collectFrontierServesEdges(sub, out);
            }
            return;
        }
        List<StepId> serves = condition.getServesSteps();
        if (serves != null) {
            out.addAll(serves);
        }
    }

    /** True if any {@code servesSteps} list anywhere in the tree is non-empty, mode-blind. */
    private static boolean carriesAnyServesEdge(CompletionCondition condition) {
        if (condition == null) return false;
        List<StepId> serves = condition.getServesSteps();
        if (serves != null && !serves.isEmpty()) {
            return true;
        }
        if (condition instanceof CompletionCondition.Composite) {
            for (CompletionCondition sub : ((CompletionCondition.Composite) condition).getConditions()) {
                if (carriesAnyServesEdge(sub)) return true;
            }
        }
        return false;
    }
}
