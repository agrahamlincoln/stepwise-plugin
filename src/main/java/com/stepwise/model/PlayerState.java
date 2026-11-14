package com.stepwise.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the plugin knows about one account, saved whole as JSON in the RS profile.
 *
 * <p>Gson restores it without running the constructor or the field initializers, so a
 * restored field holds whatever collection type Gson builds. The load calls
 * {@link #useConcurrentCollections} before the state is shared.
 */
@Getter
public class PlayerState {
    @Setter private String playerName;
    @Setter private AccountType accountType;
    private final Map<Skill, SkillState> skills = new EnumMap<>(Skill.class);
    /** Concurrent: the timer panel reads quest completion on the EDT while the client thread writes it. */
    private Map<String, QuestStatus> quests = new ConcurrentHashMap<>();
    /** Volatile: the timer panel reads it on the EDT while the client thread writes it. */
    @Setter private volatile int questPoints;
    private final Map<String, Map<String, Boolean>> achievementDiaries = new HashMap<>();
    private final Set<String> collectionLog = new HashSet<>();
    private final Map<String, Long> killCounts = new HashMap<>();
    @Setter private int combatLevel = 3;
    @Setter private int totalLevel;
    /**
     * Total XP across every skill, cached by {@link #recalculateTotalLevel}.
     *
     * <p>Cached rather than summed on read because the panel reads it on the EDT while the
     * client thread writes the skills. After a restore the skills map is whatever Gson
     * built, not an {@code EnumMap}, so iterating it during a skill's first insertion can throw.
     * Volatile so that read sees the latest total, and whole: a plain {@code long} may be
     * written in two halves (JLS 17.7).
     */
    private volatile long totalXp;

    /**
     * Creatures left on the Slayer task in hand, backing {@code noSlayerTask}.
     *
     * <p><b>{@link #SLAYER_TASK_COUNT_UNREAD} until a tick has sampled it, which is not
     * zero.</b> Zero means "no task assigned" and latches the step, so it cannot double as
     * "not read yet". The login tick returns before the Slayer sample runs, so at least one
     * resolve pass sees nothing read, and on that pass the condition must read false.
     *
     * <p>{@code transient} because it is a live reading. A restored value would read as
     * known on exactly the pass that must not trust it.
     */
    @Setter private transient int slayerTaskCount = SLAYER_TASK_COUNT_UNREAD;

    /** A Slayer task count no tick has sampled yet, told apart from holding no task. */
    public static final int SLAYER_TASK_COUNT_UNREAD = -1;
    private List<ItemStack> inventory = new ArrayList<>();
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private final Map<String, Integer> questProgress = new HashMap<>();
    private List<ItemStack> bank = new ArrayList<>();
    @Setter private Instant bankLastSeen;
    /** Tackle box contents (fishing tools plus the angler / spirit angler outfit), from
     *  InventoryID.TACKLE_BOX (651), reported only when the player opens the box. Counted
     *  toward ANYWHERE hasItem checks. */
    private List<ItemStack> tackleBox = new ArrayList<>();
    /** Seed vault contents (InventoryID.SEED_VAULT = 626), reported only when the player
     *  opens the vault in the Farming Guild. An established account keeps most seeds here
     *  rather than in the bank. Counted toward ANYWHERE hasItem checks. */
    private List<ItemStack> seedVault = new ArrayList<>();
    /** Seed box contents. The box travels with the player, so its seeds count wherever the
     *  box itself is; see {@code SeedBox}.
     *  <p>This is a tally, not a snapshot: the game never sends inventory {@code SEED_BOX}
     *  (573), so it is summed from the chat lines that announce seeds going in and out.
     *  {@code EventApplier#applySeedBoxStored} describes the accuracy cost. */
    private List<ItemStack> seedBox = new ArrayList<>();
    /** Cargo hold contents by 0-based boat slot (InventoryID.SAILING_BOAT_n_CARGOHOLD),
     *  reported only while the player looks in that hold. A slot's snapshot counts only
     *  while the boat is still owned, and is dropped when the boat goes.
     *  Concurrent: written on the client thread, read on the EDT. */
    private Map<Integer, List<ItemStack>> cargoHolds = new ConcurrentHashMap<>();
    /** Concurrent: written on the client thread, read on the EDT. */
    private Map<Integer, Integer> varbits = new ConcurrentHashMap<>();
    /** VarPlayer values for bit-level completion checks (e.g. individual diary tasks).
     *  Concurrent: written on the client thread, read on the EDT. */
    private Map<Integer, Integer> varps = new ConcurrentHashMap<>();
    /** Rune pouch contents (rune item ID → quantity), read from the pouch varbits.
     *  Pouched runes leave the inventory item array, so {@code hasItem} rune counts add
     *  them back from here.
     *  Concurrent: written on the client thread, read on the EDT. */
    private Map<Integer, Integer> runePouch = new ConcurrentHashMap<>();
    /**
     * Object ids of built scene objects this account has been seen standing next to, backing
     * {@code objectPresent} completion conditions.
     *
     * <p>Persisted because the game has no var for a furniture hotspot. This plugin's
     * sighting is the only record that the object was ever built.
     *
     * <p>Grow-only: the object leaves the scene as soon as the player walks out of the house,
     * and the step must stay complete. Bounded by {@code WatchedObjectIds}: only ids the
     * guide asks about are recorded.
     */
    private Set<Integer> observedObjects = ConcurrentHashMap.newKeySet();
    /**
     * Slayer master varbit value → how many of that master's tasks this account has been
     * watched finish, backing {@code slayerTaskComplete} completion conditions.
     *
     * <p>Persisted because the game keeps no count of finished tasks. It keeps only the
     * creatures remaining on the task in hand, which is zero both after finishing a task and
     * before ever having one.
     *
     * <p>Grow-only: auto-latch is recomputed every snapshot, so a count that could fall would
     * un-complete a finished step when the player picks up their next task.
     *
     * <p>Keyed on the raw master varbit value; {@code SlayerMasters} maps the master names a
     * guide condition uses to it.
     */
    private Map<Integer, Integer> slayerTasksCompleted = new ConcurrentHashMap<>();
    /**
     * Interactions with NPCs the guide asks about, keyed by {@code NpcInteractions.record},
     * backing {@code npcInteraction} completion conditions.
     *
     * <p>Persisted and grow-only because an interaction is an event, not a state: the game
     * keeps no var for it, and the dialogue often belongs to a quest stage the player has
     * since passed, so it cannot be observed again.
     *
     * <p>Bounded by {@code NpcInteractions}: only NPCs the guide asks about are recorded.
     */
    private Set<String> observedNpcInteractions = ConcurrentHashMap.newKeySet();

    /**
     * The map region the player is standing in, or null when unknown.
     *
     * <p>{@code transient} because it is a live reading. A region restored from the last
     * session would meet a region condition for a player who logged in elsewhere.
     */
    @Setter private transient Integer regionId;
    /**
     * Whether the bank interface is open. It gates the overlay's route-assumption reminder and
     * the reminders for {@code inventory} checks.
     *
     * <p>{@code transient} because it is a live reading: no session starts with the bank open.
     */
    @Setter private transient boolean bankOpen;

    /**
     * When the box was last <em>read</em> whole, as opposed to last heard about.
     *
     * <p>The chat lines that announce seeds going in or out keep only a tally, and a tally
     * cannot correct an over-count.
     *
     * <p>A whole-box statement sets this stamp. The item container never provides one:
     * inventory {@code SEED_BOX} (573) never fires. The Check option does: it lists the
     * contents in chat, in an empty wording and an itemised one, and either listing sets
     * this stamp.
     *
     * <p>{@code transient} because the listing vouches only for the box at the moment it was
     * read. A session that starts from a remembered tally has read nothing.
     */
    @Setter private transient Instant seedBoxVerifiedAt;

    /**
     * When seeds were last <em>watched</em> going into or out of the box, this session.
     *
     * <p>A chat line saying seeds went in is ground truth about that movement, so a tally
     * watched this session is right about at least what was watched. It cannot vouch for
     * what was in the box before. Unlike {@link #seedBoxVerifiedAt}, this stamp costs the
     * player no Check click.
     *
     * <p>{@code transient} by design: it is null on every restart, so a tally inherited from
     * a previous session earns nothing until the player moves seeds again.
     */
    @Setter private transient Instant seedBoxWatchedAt;

    public PlayerState(String playerName, AccountType accountType) {
        this.playerName = playerName;
        this.accountType = accountType;

        // Hitpoints starts at 10 / 1,154 XP; every other skill at 1 / 0.
        for (Skill skill : Skill.values()) {
            if (skill == Skill.HITPOINTS) {
                skills.put(skill, new SkillState(10, 1154));
            } else {
                skills.put(skill, new SkillState(1, 0));
            }
        }
        recalculateTotalLevel();
    }

    /**
     * Re-sum both skill totals. Call on the client thread wherever a skill changes: these
     * are what the panel reads instead of walking the map itself.
     */
    public void recalculateTotalLevel() {
        int levels = 0;
        long xp = 0;
        for (SkillState state : skills.values()) {
            levels += state.getLevel();
            xp += state.getXp();
        }
        this.totalLevel = levels;
        this.totalXp = xp;
    }

    public void recalculateCombatLevel() {
        this.combatLevel = (int) Math.floor(preciseCombatLevel());
    }

    public double preciseCombatLevel() {
        int defence = getSkillLevel(Skill.DEFENCE);
        int hitpoints = getSkillLevel(Skill.HITPOINTS);
        int prayer = getSkillLevel(Skill.PRAYER);
        int attack = getSkillLevel(Skill.ATTACK);
        int strength = getSkillLevel(Skill.STRENGTH);
        int ranged = getSkillLevel(Skill.RANGED);
        int magic = getSkillLevel(Skill.MAGIC);

        double base = (defence + hitpoints + Math.floor(prayer / 2.0)) / 4.0;
        double melee = 0.325 * (attack + strength);
        double range = 0.325 * Math.floor(ranged * 3.0 / 2.0);
        double mage = 0.325 * Math.floor(magic * 3.0 / 2.0);
        return base + Math.max(melee, Math.max(range, mage));
    }

    public int getSkillLevel(Skill skill) {
        SkillState state = skills.get(skill);
        return state != null ? state.getLevel() : 1;
    }

    // Coins are not cached here: ResolutionEngine.countGpByLocation counts them on demand
    // from inventory and bank, the path gpThreshold conditions and the route comparison read.

    public void setInventory(List<ItemStack> inventory) { this.inventory = new ArrayList<>(inventory); }

    /** Replaces the rune pouch contents with a freshly-read snapshot. */
    public void setRunePouch(Map<Integer, Integer> contents) {
        Map<Integer, Integer> next = new ConcurrentHashMap<>();
        if (contents != null) { next.putAll(contents); }
        this.runePouch = next;
    }

    public void setBank(List<ItemStack> bank) { this.bank = new ArrayList<>(bank); }

    public void setTackleBox(List<ItemStack> tackleBox) { this.tackleBox = new ArrayList<>(tackleBox); }

    public void setSeedVault(List<ItemStack> seedVault) { this.seedVault = new ArrayList<>(seedVault); }

    public void setSeedBox(List<ItemStack> seedBox) { this.seedBox = new ArrayList<>(seedBox); }

    /** The last-seen contents of one boat slot's hold, or an empty list if never seen. */
    public List<ItemStack> getCargoHold(int slot) {
        List<ItemStack> hold = cargoHolds.get(slot);
        return hold != null ? hold : List.of();
    }

    /** Replaces one boat slot's hold with a freshly-read snapshot. */
    public void setCargoHold(int slot, List<ItemStack> items) {
        cargoHolds.put(slot, new ArrayList<>(items));
    }

    /** Forgets one boat slot's hold, for a boat the player no longer has. */
    public void clearCargoHold(int slot) {
        cargoHolds.remove(slot);
    }

    /**
     * Swap each collection the client thread writes and the EDT reads for a concurrent one,
     * after a Gson restore has left it as whatever Gson built.
     *
     * <p>Call once, on the restoring thread, before the state is shared. Wrapping lazily in a
     * getter instead would let the client thread and the EDT each install their own copy of
     * the same map, and the writes made to the copy that lost would vanish.
     */
    public void useConcurrentCollections() {
        cargoHolds = concurrent(cargoHolds);
        quests = concurrent(quests);
        varbits = concurrent(varbits);
        varps = concurrent(varps);
        runePouch = concurrent(runePouch);
        observedObjects = concurrent(observedObjects);
        slayerTasksCompleted = concurrent(slayerTasksCompleted);
        observedNpcInteractions = concurrent(observedNpcInteractions);
    }

    /**
     * {@code map} itself when concurrent, else a concurrent copy of a Gson-restored one. Gson
     * builds this class without a constructor, so a save missing the key restores null.
     */
    private static <K, V> Map<K, V> concurrent(Map<K, V> map) {
        if (map instanceof ConcurrentHashMap) {
            return map;
        }
        return map == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(map);
    }

    /** As {@link #concurrent(Map)}, for a set. */
    private static <T> Set<T> concurrent(Set<T> set) {
        if (set instanceof ConcurrentHashMap.KeySetView) {
            return set;
        }
        Set<T> copy = ConcurrentHashMap.newKeySet();
        if (set != null) {
            copy.addAll(set);
        }
        return copy;
    }
}
