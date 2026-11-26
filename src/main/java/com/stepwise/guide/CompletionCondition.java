package com.stepwise.guide;

import com.google.common.collect.ImmutableBiMap;
import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Value;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@EqualsAndHashCode
public abstract class CompletionCondition {
    /** Wire name of every condition type, the {@code type} key of its guide JSON. */
    private static final ImmutableBiMap<String, Class<? extends CompletionCondition>> TYPES =
            ImmutableBiMap.<String, Class<? extends CompletionCondition>>builder()
                    .put("skillLevel", SkillLevel.class)
                    .put("combatLevel", CombatLevel.class)
                    .put("skillXp", SkillXp.class)
                    .put("questComplete", QuestComplete.class)
                    .put("gpThreshold", GpThreshold.class)
                    .put("hasItem", HasItem.class)
                    .put("hasItemTotal", HasItemTotal.class)
                    .put("diaryComplete", DiaryComplete.class)
                    .put("diaryReward", DiaryReward.class)
                    .put("combatAchievement", CombatAchievement.class)
                    .put("xpGained", XpGained.class)
                    .put("killCount", KillCount.class)
                    .put("collectionLog", CollectionLog.class)
                    .put("locationReached", LocationReached.class)
                    .put("objectPresent", ObjectPresent.class)
                    .put("questStarted", QuestStarted.class)
                    .put("slayerTaskComplete", SlayerTaskComplete.class)
                    .put("noSlayerTask", NoSlayerTask.class)
                    .put("npcInteraction", NpcInteraction.class)
                    .put("questProgress", QuestProgress.class)
                    .put("varbitValue", VarbitValue.class)
                    .put("varpBitValue", VarpBitValue.class)
                    .put("varpValue", VarpValue.class)
                    .put("manual", Manual.class)
                    .put("composite", Composite.class)
                    .put("boatOwned", BoatOwned.class)
                    .put("boatComponent", BoatComponent.class)
                    .put("boatFacility", BoatFacility.class)
                    .build();

    /**
     * Advisory flag, meaningful only inside a {@link Composite}. An optional condition is
     * still evaluated and shown for progress but never counts toward the composite's
     * pass/fail, so it cannot block step completion. It can decorate any condition type, so
     * it lives on the base class. {@code transient} so Gson's reflective adapter ignores it;
     * only {@link TypeAdapter} reads {@code optional}.
     */
    private transient boolean optional;

    /**
     * Marks an arm of an any-of {@link Composite} that shows the player already got past the
     * step another way, such as a later quest stage. It completes the step like any arm, but
     * the step card leaves it out, so the card lists only the step's own work.
     */
    private transient boolean catchUp;

    /**
     * Step IDs that this condition serves. When a served step is already completed, the
     * resolution engine waives this condition. Null when the guide omits it, because Gson
     * bypasses the constructors; {@link #getServesSteps()} reads that as none.
     */
    @SerializedName("servesSteps")
    private final List<StepId> servesSteps;

    /** @param servesSteps the served step IDs, or null for none */
    protected CompletionCondition(List<StepId> servesSteps) {
        this.servesSteps = servesSteps;
    }

    public final String getType() {
        return TYPES.inverse().get(getClass());
    }

    public final boolean isOptional() {
        return optional;
    }

    public final boolean isCatchUp() {
        return catchUp;
    }

    public final List<StepId> getServesSteps() {
        return servesSteps != null ? servesSteps : Collections.emptyList();
    }

    /** Rejects a parsed condition that could never be met. */
    void checkParsed() {
    }

    static void requireNonEmpty(List<?> list, String field, String type) {
        if (list == null || list.isEmpty()) {
            throw new JsonParseException("Missing or empty '" + field + "' in " + type + " condition");
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class SkillLevel extends CompletionCondition {
        @SerializedName("skill") String skill;
        @SerializedName("level") int level;

        public SkillLevel(String skill, int level) {
            this(skill, level, null);
        }

        public SkillLevel(String skill, int level, List<StepId> servesSteps) {
            super(servesSteps);
            this.skill = skill;
            this.level = level;
        }
    }

    /**
     * Player reaches a minimum combat level.
     *
     * <p>Combat level is derived from seven skills, so {@link SkillLevel} cannot express it:
     * {@link com.stepwise.model.Skill#fromString} does not resolve "Combat", and the step would
     * never complete. This reads the derived value from {@link com.stepwise.model.PlayerState}.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class CombatLevel extends CompletionCondition {
        @SerializedName("level") int level;

        public CombatLevel(int level) {
            this(level, null);
        }

        public CombatLevel(int level, List<StepId> servesSteps) {
            super(servesSteps);
            this.level = level;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class SkillXp extends CompletionCondition {
        @SerializedName("skill") String skill;
        @SerializedName("minXp") long minXp;

        public SkillXp(String skill, long minXp) {
            this(skill, minXp, null);
        }

        public SkillXp(String skill, long minXp, List<StepId> servesSteps) {
            super(servesSteps);
            this.skill = skill;
            this.minXp = minXp;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class QuestComplete extends CompletionCondition {
        @SerializedName("questName") String questName;

        public QuestComplete(String questName) {
            this(questName, null);
        }

        public QuestComplete(String questName, List<StepId> servesSteps) {
            super(servesSteps);
            this.questName = questName;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class GpThreshold extends CompletionCondition {
        @SerializedName("minGp") long minGp;
        @SerializedName("location") ItemLocation location;

        public GpThreshold(long minGp, ItemLocation location) {
            this(minGp, location, null);
        }

        public GpThreshold(long minGp, ItemLocation location, List<StepId> servesSteps) {
            super(servesSteps);
            this.minGp = minGp;
            this.location = location;
        }
    }

    /** Entry in the consumedAt array: a step where this item is consumed, with optional quantity. */
    @Value
    public static class ConsumedAtEntry {
        @SerializedName("stepId") String stepId;
        /** Quantity consumed at this step. Null means variable/unknown — don't reduce threshold. */
        @SerializedName("quantity") Integer quantity;
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class HasItem extends CompletionCondition {
        @SerializedName("itemName") String itemName;
        @SerializedName("itemId") int itemId;
        @SerializedName("quantity") int quantity;
        @SerializedName("location") ItemLocation location;
        /** Item class for equivalence group matching (e.g. "hammer", "axe"). Null when exact ID matching. */
        @SerializedName("itemClass") String itemClass;
        /** Minimum tier for tiered tool classes (e.g. 41 = rune-tier axe). Null when not tiered. */
        @SerializedName("minTier") Integer minTier;
        /** Steps where this item is consumed (destroyed). Null in the guide for tools/persistent items. */
        @SerializedName("consumedAt") List<ConsumedAtEntry> consumedAt;

        public HasItem(String itemName, int itemId, int quantity, ItemLocation location) {
            this(itemName, itemId, quantity, location, null, null, null, null);
        }

        public HasItem(String itemName, int itemId, int quantity, ItemLocation location, List<StepId> servesSteps) {
            this(itemName, itemId, quantity, location, servesSteps, null, null, null);
        }

        public HasItem(String itemName, int itemId, int quantity, ItemLocation location, List<StepId> servesSteps, String itemClass, Integer minTier) {
            this(itemName, itemId, quantity, location, servesSteps, itemClass, minTier, null);
        }

        public HasItem(String itemName, int itemId, int quantity, ItemLocation location, List<StepId> servesSteps, String itemClass, Integer minTier, List<ConsumedAtEntry> consumedAt) {
            super(servesSteps);
            this.itemName = itemName;
            this.itemId = itemId;
            this.quantity = quantity;
            this.location = location;
            this.itemClass = itemClass;
            this.minTier = minTier;
            this.consumedAt = consumedAt;
        }

        public List<ConsumedAtEntry> getConsumedAt() {
            return consumedAt != null ? consumedAt : Collections.emptyList();
        }
    }

    /** A single interchangeable item in a {@link HasItemTotal} condition. */
    @Value
    public static class ItemRef {
        @SerializedName("itemName") String itemName;
        @SerializedName("itemId") int itemId;
    }

    /**
     * Met when the SUM of the player's counts of ALL listed item IDs (in
     * {@code location}, default {@link ItemLocation#ANYWHERE}) reaches {@code quantity}.
     *
     * <p>Models "stockpile N of interchangeable items" where any mix counts, such as bird-house
     * seeds across several seed types. A {@link Composite} of {@link HasItem} cannot express
     * this, because each sub-condition imposes its own per-item threshold.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class HasItemTotal extends CompletionCondition {
        @SerializedName("items") List<ItemRef> items;
        @SerializedName("quantity") int quantity;
        @SerializedName("location") ItemLocation location;

        public HasItemTotal(List<ItemRef> items, int quantity, ItemLocation location) {
            this(items, quantity, location, null);
        }

        public HasItemTotal(List<ItemRef> items, int quantity, ItemLocation location, List<StepId> servesSteps) {
            super(servesSteps);
            this.items = items;
            this.quantity = quantity;
            this.location = location;
        }

        /** With no items the sum is always zero, so the step could never complete. */
        @Override
        void checkParsed() {
            requireNonEmpty(items, "items", "hasItemTotal");
        }

        /**
         * The one name a player uses for this set, or {@code null} when the items share none.
         *
         * <p>Most sets are one item in several forms (a ring and its imbued variant, grimy and
         * clean herbs), so the panel can name that item. The name is the longest run of leading
         * words every item shares ("Berserker ring" from "Berserker ring" / "Berserker ring (i)"),
         * or failing that the longest run of trailing words ("snail" from "Thin snail" /
         * "Fat snail"). Matching ignores case; the spelling follows the first item.
         *
         * @return the shared item name with a capitalised first letter, or {@code null}
         */
        public String displayName() {
            if (items.isEmpty()) {
                return null;
            }
            List<String[]> tokenised = new ArrayList<>(items.size());
            for (ItemRef ref : items) {
                String name = ref.getItemName() == null ? "" : ref.getItemName().trim();
                if (name.isEmpty()) {
                    return null;
                }
                tokenised.add(name.split("\\s+"));
            }
            String[] first = tokenised.get(0);
            int shortest = Integer.MAX_VALUE;
            for (String[] tokens : tokenised) {
                shortest = Math.min(shortest, tokens.length);
            }

            int prefix = 0;
            while (prefix < shortest && allMatchAt(tokenised, prefix, false)) {
                prefix++;
            }
            if (prefix > 0) {
                return capitalise(String.join(" ", Arrays.copyOfRange(first, 0, prefix)));
            }

            int suffix = 0;
            while (suffix < shortest && allMatchAt(tokenised, suffix, true)) {
                suffix++;
            }
            if (suffix > 0) {
                return capitalise(String.join(" ",
                        Arrays.copyOfRange(first, first.length - suffix, first.length)));
            }
            return null;
        }

        /** Whether every tokenised name has the same word at offset {@code i} from the start or end. */
        private static boolean allMatchAt(List<String[]> tokenised, int i, boolean fromEnd) {
            String[] first = tokenised.get(0);
            String want = fromEnd ? first[first.length - 1 - i] : first[i];
            for (String[] tokens : tokenised) {
                String have = fromEnd ? tokens[tokens.length - 1 - i] : tokens[i];
                if (!have.equalsIgnoreCase(want)) {
                    return false;
                }
            }
            return true;
        }

        private static String capitalise(String s) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class DiaryComplete extends CompletionCondition {
        @SerializedName("area") String area;
        @SerializedName("tier") String tier;

        public DiaryComplete(String area, String tier) {
            this(area, tier, null);
        }

        public DiaryComplete(String area, String tier, List<StepId> servesSteps) {
            super(servesSteps);
            this.area = area;
            this.tier = tier;
        }
    }

    /**
     * The tier's reward has been claimed from the taskmaster. Claiming happens after, and
     * separately from, finishing the tasks ({@link DiaryComplete}). It fits a step that
     * collects the reward or spends its lamp.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class DiaryReward extends CompletionCondition {
        @SerializedName("area") String area;
        @SerializedName("tier") String tier;

        public DiaryReward(String area, String tier) {
            this(area, tier, null);
        }

        public DiaryReward(String area, String tier, List<StepId> servesSteps) {
            super(servesSteps);
            this.area = area;
            this.tier = tier;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class CombatAchievement extends CompletionCondition {
        @SerializedName("name") String name;
        /**
         * RuneLite varbit ID the task's completion is read from. Null means no varbit reports
         * it, so only Mark Done completes it.
         */
        @SerializedName("varbitId") Integer varbitId;

        public CombatAchievement(String name) {
            this(name, null, null);
        }

        public CombatAchievement(String name, Integer varbitId, List<StepId> servesSteps) {
            super(servesSteps);
            this.name = name;
            this.varbitId = varbitId;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class XpGained extends CompletionCondition {
        @SerializedName("skill") String skill;
        @SerializedName("minXp") long minXp;

        public XpGained(String skill, long minXp) {
            this(skill, minXp, null);
        }

        public XpGained(String skill, long minXp, List<StepId> servesSteps) {
            super(servesSteps);
            this.skill = skill;
            this.minXp = minXp;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class KillCount extends CompletionCondition {
        @SerializedName("target") String target;
        @SerializedName("count") int count;

        public KillCount(String target, int count) {
            this(target, count, null);
        }

        public KillCount(String target, int count, List<StepId> servesSteps) {
            super(servesSteps);
            this.target = target;
            this.count = count;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class CollectionLog extends CompletionCondition {
        @SerializedName("itemName") String itemName;
        /** OSRS item ID for this collection log entry, or null when the entry has none. */
        @SerializedName("itemId") Integer itemId;

        public CollectionLog(String itemName) {
            this(itemName, null, null);
        }

        public CollectionLog(String itemName, Integer itemId, List<StepId> servesSteps) {
            super(servesSteps);
            this.itemName = itemName;
            this.itemId = itemId;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class LocationReached extends CompletionCondition {
        @SerializedName("regionId") int regionId;
        /** Human-readable name of the region. */
        @SerializedName("regionName") String regionName;

        public LocationReached(int regionId, String regionName) {
            this(regionId, regionName, null);
        }

        public LocationReached(int regionId, String regionName, List<StepId> servesSteps) {
            super(servesSteps);
            this.regionId = regionId;
            this.regionName = regionName;
        }
    }

    /**
     * A built object has been seen in the loaded scene.
     *
     * <p>For built work the game exposes no var for, such as player-owned-house furniture,
     * whose hotspot contents live only in the scene. Nothing here is house-specific.
     *
     * <p><b>Any-of.</b> Met when <em>any</em> id in {@code objectIds} has been seen. Each
     * construction tier is its own object id, so "an oak bed or better" lists the bed tiers from
     * oak upward. A single-element list means exactly that object.
     *
     * <p><b>Matching is plain set membership, so every accepted tier id appears in the
     * list.</b> A numeric range would be wrong, as RuneLite's {@code ObjectID} shows:
     * <ul>
     *   <li><b>Families are adjacent.</b> {@code POH_BED_1..7} is 13148-13154, and 13155 is
     *       {@code POH_WARDROBE_1}.
     *   <li><b>Tiers are not contiguous.</b> {@code POH_STOVE_1..7} is 13528, 13529, 13531,
     *       13533, 13536, 13539, 13542; the gaps hold {@code _KETTLE} and {@code _POT} variants.
     * </ul>
     *
     * <p><b>Monotonic.</b> The evidence is the sighting, not the scene (see
     * {@code ResolutionEngine.isMonotonic}), so the step stays complete after the player leaves.
     *
     * <p><b>Absence proves nothing.</b> The player may have built the object without standing
     * beside it while the plugin ran. Serves-completion may therefore still back-fill this type
     * (see {@code ResolutionEngine.unmetProvesIncomplete}), through a serves edge on the step
     * or a durable composite arm.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class ObjectPresent extends CompletionCondition {
        /** Human-readable name of the object, e.g. "Oak bed". Player-facing. */
        @SerializedName("objectName") String objectName;
        /** Object ids that satisfy this condition, any one of them. Never empty. */
        @SerializedName("objectIds") List<Integer> objectIds;
        /**
         * Item whose icon depicts this build, or {@code null} where the game pairs none.
         * Display only; it plays no part in matching.
         *
         * <p>A built object has no drawable image. The game's per-furniture picture is an
         * interface item that exists only to be drawn. The guide lists this separately from
         * {@code objectIds}.
         *
         * <p>Null for most objects, so callers need a fallback. The flatpack item is no
         * substitute: every flatpack shares one silhouette differing only in tint.
         */
        @SerializedName("iconItemId") Integer iconItemId;

        public ObjectPresent(String objectName, List<Integer> objectIds) {
            this(objectName, objectIds, null, null);
        }

        public ObjectPresent(String objectName, List<Integer> objectIds, List<StepId> servesSteps) {
            this(objectName, objectIds, servesSteps, null);
        }

        public ObjectPresent(String objectName, List<Integer> objectIds, List<StepId> servesSteps,
                Integer iconItemId) {
            super(servesSteps);
            this.objectName = objectName;
            this.objectIds = objectIds;
            this.iconItemId = iconItemId;
        }

        /** With no ids the any-of test is always false. */
        @Override
        void checkParsed() {
            requireNonEmpty(objectIds, "objectIds", "objectPresent");
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class QuestStarted extends CompletionCondition {
        @SerializedName("questName") String questName;

        public QuestStarted(String questName) {
            this(questName, null);
        }

        public QuestStarted(String questName, List<StepId> servesSteps) {
            super(servesSteps);
            this.questName = questName;
        }
    }

    /**
     * The player has killed out an assigned Slayer task in full.
     *
     * <p>Read against what the plugin has watched happen, never against live game state, as
     * with {@link ObjectPresent}. The game keeps no count of finished tasks, only the creatures
     * left on the current one, which reads zero both after a task and before any task. This
     * reads {@link com.stepwise.model.PlayerState#getSlayerTasksCompleted()}, maintained by
     * {@code com.stepwise.engine.SlayerTaskTracker}.
     *
     * <p>This type is monotonic, so it is trusted at any distance from the prerequisite
     * frontier. A live "no task" reading would therefore latch for a player who has not
     * started. {@link NoSlayerTask} reads the task-count varp safely because it is not
     * monotonic.
     *
     * <p>A task finished before the plugin was installed leaves no evidence. Such a step
     * completes through a serves edge, a {@link NoSlayerTask} composite arm, or a milestone
     * above it. Like {@code objectPresent}, this type stays open to the backward serves walk.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class SlayerTaskComplete extends CompletionCondition {
        /**
         * Slayer master who assigned the task, e.g. "Vannaka". Null counts a task from any
         * master. Matched case-insensitively against {@link SlayerMasters}.
         */
        @SerializedName("master") String master;
        /**
         * How many completed tasks satisfy this step. At least 1.
         *
         * <p>A threshold on the account's running total, not a per-step quota. Two steps
         * asking {@code count: 1} of the same master are both met by the first task. A second
         * task from that master meets {@code count: 2}.
         */
        @SerializedName("count") int count;

        public SlayerTaskComplete(String master, int count) {
            this(master, count, null);
        }

        public SlayerTaskComplete(String master, int count, List<StepId> servesSteps) {
            super(servesSteps);
            this.master = master;
            this.count = count;
        }

        /** A count below 1, or one the guide omits, would latch on sight; it reads as 1. */
        public int getCount() {
            return Math.max(count, 1);
        }
    }

    /**
     * The player is holding no Slayer task.
     *
     * <p>The catch-up arm for {@link SlayerTaskComplete}, which credits only tasks the plugin
     * watched. The guide assumes it is being followed: a player at a "finish your task" step with
     * no task in hand finished the one the route gave them.
     *
     * <p><b>Deliberately not monotonic</b>, and that is its safety. A non-monotonic condition is
     * trusted only at the prerequisite frontier ({@code CoreEngine.isAutoLatchable}), so it does
     * not fire for a player who has never had a task. It backs {@code slayerTaskComplete}
     * inside an {@code any} composite. The pre-scan reads an {@code any} composite through its
     * durable arms only, so the watched tally still latches at any distance while this arm
     * waits for the player to arrive.
     *
     * <p><b>The gate is only as strong as the step's prerequisites.</b> A step with none is
     * always at the frontier, so this would latch for anyone not holding a task. A milestone
     * criterion cannot use it either: {@code MilestoneLatcher} applies no frontier check.
     *
     * <p>Once latched the step stays latched. The retract pass reconsiders only
     * inventory-scoped conditions, so taking the next task does not undo it.
     */
    public static class NoSlayerTask extends CompletionCondition {
        public NoSlayerTask() {
            super(null);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof NoSlayerTask;
        }

        @Override
        public int hashCode() {
            return NoSlayerTask.class.hashCode();
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class NpcInteraction extends CompletionCondition {
        @SerializedName("npcName") String npcName;
        @SerializedName("action") String action;

        public NpcInteraction(String npcName, String action) {
            this(npcName, action, null);
        }

        public NpcInteraction(String npcName, String action, List<StepId> servesSteps) {
            super(servesSteps);
            this.npcName = npcName;
            this.action = action;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class QuestProgress extends CompletionCondition {
        @SerializedName("questName") String questName;
        @SerializedName("minValue") int minValue;
        /**
         * The checkpoint the game records at {@code minValue}, written as a full sentence. Null
         * shows the quest stage before {@code minValue} instead.
         */
        @SerializedName("taskDescription") String taskDescription;

        public QuestProgress(String questName, int minValue) {
            this(questName, minValue, null, null);
        }

        public QuestProgress(String questName, int minValue, List<StepId> servesSteps) {
            this(questName, minValue, null, servesSteps);
        }

        public QuestProgress(String questName, int minValue, String taskDescription, List<StepId> servesSteps) {
            super(servesSteps);
            this.questName = questName;
            this.minValue = minValue;
            this.taskDescription = taskDescription;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class VarbitValue extends CompletionCondition {
        @SerializedName("varbitId") int varbitId;
        @SerializedName("minValue") int minValue;
        /**
         * Optional upper bound: the varbit must satisfy {@code minValue <= v <= maxValue}. Set
         * equal to minValue for an exact match. Required for categorical varbits (e.g. POH house
         * location, varbit 2187) where a higher value is a different state, not more progress.
         * Null means open-ended ({@code v >= minValue}).
         */
        @SerializedName("maxValue") Integer maxValue;
        /** Human-readable description, e.g. "Charge the scrying orb at the Wizards' Tower". */
        @SerializedName("taskDescription") String taskDescription;
        /**
         * What the panel draws beside the row, since a var id means nothing to a player:
         * {@code item:<id>}, {@code skill:<Skill>}, {@code quest}, {@code diary}, {@code gp} or
         * {@code none}. Null draws nothing.
         */
        @SerializedName("icon") String icon;

        public VarbitValue(int varbitId, int minValue, String taskDescription) {
            this(varbitId, minValue, null, taskDescription, null);
        }

        public VarbitValue(int varbitId, int minValue, String taskDescription, List<StepId> servesSteps) {
            this(varbitId, minValue, null, taskDescription, servesSteps);
        }

        public VarbitValue(int varbitId, int minValue, Integer maxValue, String taskDescription, List<StepId> servesSteps) {
            super(servesSteps);
            this.varbitId = varbitId;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.taskDescription = taskDescription;
            this.icon = null;
        }
    }

    /** Checks that a specific bit within a VarPlayer value is set (e.g. diary tasks). */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class VarpBitValue extends CompletionCondition {
        @SerializedName("varpId") int varpId;
        @SerializedName("bit") int bit;
        /**
         * Human-readable diary task description, e.g. "Ardougne Easy: Enter the Combat Training
         * Camp".
         */
        @SerializedName("taskDescription") String taskDescription;
        /** The panel's icon for the row, as on {@link VarbitValue#icon}. */
        @SerializedName("icon") String icon;

        public VarpBitValue(int varpId, int bit, String taskDescription) {
            this(varpId, bit, taskDescription, null);
        }

        public VarpBitValue(int varpId, int bit, String taskDescription, List<StepId> servesSteps) {
            super(servesSteps);
            this.varpId = varpId;
            this.bit = bit;
            this.taskDescription = taskDescription;
            this.icon = null;
        }
    }

    /**
     * Checks that a whole VarPlayer value reaches a threshold ({@code value >= minValue}).
     *
     * <p>Compares the varp's numeric value, where {@link VarpBitValue} tests a flag bit.
     * Example: the bird-house space varps (1626-1629) encode house tier and seed.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class VarpValue extends CompletionCondition {
        @SerializedName("varp") int varp;
        @SerializedName("minValue") int minValue;
        /** Human-readable task description, e.g. "Build a bird house at the northern space". */
        @SerializedName("taskDescription") String taskDescription;
        /** The panel's icon for the row, as on {@link VarbitValue#icon}. */
        @SerializedName("icon") String icon;

        public VarpValue(int varp, int minValue, String taskDescription) {
            this(varp, minValue, taskDescription, null);
        }

        public VarpValue(int varp, int minValue, String taskDescription, List<StepId> servesSteps) {
            super(servesSteps);
            this.varp = varp;
            this.minValue = minValue;
            this.taskDescription = taskDescription;
            this.icon = null;
        }
    }

    public static class Manual extends CompletionCondition {
        public static final Manual INSTANCE = new Manual();

        private Manual() {
            super(null);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Manual;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class Composite extends CompletionCondition {
        @SerializedName("conditions") List<CompletionCondition> conditions;
        @SerializedName("mode") CompositeMode mode;
        /**
         * One-line label for the whole composite, or null when the arms are
         * described individually.
         */
        @SerializedName("taskDescription") String taskDescription;

        public Composite(List<CompletionCondition> conditions, CompositeMode mode,
                          List<StepId> servesSteps, String taskDescription) {
            super(servesSteps);
            this.conditions = conditions;
            this.mode = mode;
            this.taskDescription = taskDescription;
        }

        public Composite(List<CompletionCondition> conditions, CompositeMode mode,
                          List<StepId> servesSteps) {
            this(conditions, mode, servesSteps, null);
        }

        public Composite(List<CompletionCondition> conditions, CompositeMode mode) {
            this(conditions, mode, null, null);
        }

        /**
         * Whether a {@code taskDescription} replaces the per-arm rows and the
         * generated "N of M" summary. Blank text counts as absent.
         */
        public boolean hasTaskDescription() {
            return taskDescription != null && !taskDescription.isBlank();
        }
    }

    /**
     * The player owns a boat of exactly the named type ("raft", "skiff" or "sloop") in any of
     * the five Sailing boat slots. A higher-tier boat never stands in for a lower one, because
     * the route keeps upgrading the skiff after the sloop exists.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class BoatOwned extends CompletionCondition {
        @SerializedName("boatType") String boatType;

        public BoatOwned(String boatType) {
            this(boatType, null);
        }

        public BoatOwned(String boatType, List<StepId> servesSteps) {
            super(servesSteps);
            this.boatType = boatType;
        }
    }

    /**
     * A part on the player's boat of the named type has reached at least {@code minTier}.
     * Tiers are the game's own numbering: metal parts (the keel, and the helm, keyed
     * {@code steering}) run bronze=0 through dragon=6, wood parts (the hull, and the mast and
     * sails, keyed {@code sail}) wooden=0 through rosewood=6, and the brazier is 1 once
     * built. A bare boat is therefore tier 0 in every part, so a threshold of 1 means "upgraded".
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class BoatComponent extends CompletionCondition {
        @SerializedName("boatType") String boatType;
        @SerializedName("component") String component;
        @SerializedName("minTier") int minTier;

        public BoatComponent(String boatType, String component, int minTier) {
            this(boatType, component, minTier, null);
        }

        public BoatComponent(String boatType, String component, int minTier, List<StepId> servesSteps) {
            super(servesSteps);
            this.boatType = boatType;
            this.component = component;
            this.minTier = minTier;
        }
    }

    /**
     * A named facility is installed on the boat of exactly {@code boatType}.
     *
     * <p>Facility hotspot varbits are indexed by position, and the index differs per boat type.
     * The engine finds the slot by boat type and reads the facility's hotspot from a
     * per-facility table. An unknown facility name never latches.
     */
    @Value
    @EqualsAndHashCode(callSuper = true)
    public static class BoatFacility extends CompletionCondition {
        @SerializedName("boatType") String boatType;
        @SerializedName("facility") String facility;

        public BoatFacility(String boatType, String facility) {
            this(boatType, facility, null);
        }

        public BoatFacility(String boatType, String facility, List<StepId> servesSteps) {
            super(servesSteps);
            this.boatType = boatType;
            this.facility = facility;
        }
    }

    /**
     * Polymorphic Gson deserializer for every CompletionCondition type, keyed on {@code type}.
     * Each type's fields bind reflectively; {@code optional} and {@code catchUp} decorate any
     * type, so they are read here.
     */
    public static class TypeAdapter implements JsonDeserializer<CompletionCondition> {
        @Override
        public CompletionCondition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            JsonElement typeElement = obj.get("type");
            if (typeElement == null || typeElement.isJsonNull()) {
                throw new JsonParseException("Missing 'type' field in CompletionCondition");
            }
            Class<? extends CompletionCondition> type = TYPES.get(typeElement.getAsString());
            if (type == null) {
                throw new JsonParseException("Unknown CompletionCondition type: " + typeElement.getAsString());
            }
            CompletionCondition condition = context.deserialize(json, type);
            condition.checkParsed();
            condition.optional = flag(obj, "optional");
            condition.catchUp = flag(obj, "catchUp");
            return condition;
        }

        private static boolean flag(JsonObject obj, String name) {
            JsonElement value = obj.get(name);
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        }
    }
}
