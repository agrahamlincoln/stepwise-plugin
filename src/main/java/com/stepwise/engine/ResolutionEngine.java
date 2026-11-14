package com.stepwise.engine;

import com.stepwise.guide.CompletionCondition;
import com.stepwise.guide.CompletionCondition.*;
import com.stepwise.guide.CompositeMode;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.ItemLocation;
import com.stepwise.guide.NpcInteractions;
import com.stepwise.guide.SlayerMasters;
import com.stepwise.guide.StepId;
import com.stepwise.guide.StepStatus;
import com.stepwise.diary.DiaryVarbits;
import com.stepwise.model.ItemStack;
import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import com.stepwise.model.Skill;
import com.stepwise.model.SkillState;
import com.stepwise.sailing.BoatVarbits;
import com.stepwise.snapshot.ResolvedStep;
import lombok.Value;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ResolutionEngine {

    private ResolutionEngine() {
    }

    private static volatile ItemClassResolver itemClassResolver;

    /**
     * Initialize the item class resolver. Call once at plugin startup. Until then, class-based
     * matching falls back to the exact id.
     */
    public static void initItemClasses(ItemClassResolver resolver) {
        itemClassResolver = resolver;
    }

    /**
     * Item ids that satisfy an "items needed" row's identity. Mirrors the class-vs-id branch
     * of {@link #conditionMet}'s {@code HasItem} arm, so rows and completion conditions
     * agree on which items count.
     *
     * <p>Callers get the id set, never the resolver, so counting stays in
     * {@link #countItemsByIds} and its ownership-aware callers.
     *
     * @return every accepted id; empty for a text-only row (neither field set). Without an
     *         initialized resolver, a singleton of {@code itemId}
     */
    static Set<Integer> acceptedIdsFor(Integer itemId, String itemClass) {
        if (itemClass != null && itemClassResolver != null) {
            Set<Integer> members = itemClassResolver.resolveValidIds(itemClass, null);
            if (!members.isEmpty()) {
                return members;
            }
            // A class the bundled table does not know: fall back to the row's own id.
        }
        if (itemId != null && itemId > 0) {
            if (itemClassResolver != null) {
                Set<Integer> equivalent = itemClassResolver.resolveEquivalentIds(itemId);
                if (equivalent != null) {
                    return equivalent;
                }
            }
            // A class row still carries its first member's id, a narrower but true identity.
            return Collections.singleton(itemId);
        }
        return Collections.emptySet();
    }

    /**
     * Item ids a {@link HasItem} condition counts, shared by {@link #conditionMet} and the
     * panel's count of the same condition so the two always agree.
     *
     * @return the item class's members, or the id with its upgrade and charge equivalents;
     *         null when the condition counts by its own id or name alone
     */
    static Set<Integer> countedIds(HasItem c) {
        // Read once: a plugin shutdown on another thread can clear the field mid-call.
        ItemClassResolver resolver = itemClassResolver;
        if (resolver == null) {
            return null;
        }
        // A class the bundled table does not list counts by the condition's own id, as
        // acceptedIdsFor does for an items-needed row. A listed class whose minTier excludes
        // every member still counts nothing.
        if (c.getItemClass() != null && !resolver.resolveValidIds(c.getItemClass(), null).isEmpty()) {
            return resolver.resolveValidIds(c.getItemClass(), c.getMinTier());
        }
        // Upgrade-chain members and charge variants count too (a 5-charge item satisfies a
        // condition keyed on the 1-charge id).
        return c.getItemId() > 0 ? resolver.resolveEquivalentIds(c.getItemId()) : null;
    }

    /** Every value is a quest name exactly as RuneLite's {@code Quest.getName()} returns it. */
    static final Map<String, String> QUEST_NAME_ALIASES;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("romeo and juliet", "Romeo & Juliet");
        m.put("priests in peril", "Priest in Peril");
        m.put("desert treasure", "Desert Treasure I");
        m.put("fairytale i", "Fairytale I - Growing Pains");
        m.put("fairytale ii", "Fairytale II - Cure a Queen");
        m.put("forgettable tale", "Forgettable Tale...");
        m.put("pirates' treasure", "Pirate's Treasure");
        m.put("spirits of the elids", "Spirits of the Elid");
        m.put("legend's quest", "Legends' Quest");
        m.put("the client of kourend", "Client of Kourend");
        m.put("desert treasure ii", "Desert Treasure II - The Fallen Empire");
        m.put("dragon slayer", "Dragon Slayer I");
        m.put("monkey madness", "Monkey Madness I");
        m.put("rag and bone man", "Rag and Bone Man I");
        m.put("rfd - evil dave", "Recipe for Disaster - Evil Dave");
        QUEST_NAME_ALIASES = Collections.unmodifiableMap(m);
    }

    /**
     * Maps a quest name as the guide writes it to RuneLite's Quest.getName(), which titles some
     * quests differently: the guide's "Romeo and Juliet" and "Dragon Slayer" are RuneLite's
     * "Romeo &amp; Juliet" and "Dragon Slayer I".
     *
     * <p>Player state is keyed by {@code Quest.getName()}, so an alias value that is not a real
     * quest name makes every condition for that quest a permanent miss.
     *
     * @param name quest name as written in the guide data; may be null
     * @return the exact RuneLite quest name, or {@code name} unchanged if no alias applies
     */
    static String normalizeQuestName(String name) {
        if (name == null) {
            return null;
        }
        String alias = QUEST_NAME_ALIASES.get(name.toLowerCase(Locale.ROOT));
        return alias != null ? alias : name;
    }

    public static List<ResolvedStep> resolveSteps(
            PlayerState state,
            StepwiseGuide guide,
            Set<StepId> completedOverrides,
            Set<StepId> skipped,
            Set<StepId> suppressedAutoLatch) {

        Map<StepId, StepStatus> resolved = new LinkedHashMap<>();
        List<ResolvedStep> results = new ArrayList<>();
        boolean hasActive = false;

        // The steps that waive the conditions serving them. Seeded with the completed steps;
        // the scans below add steps already met, and the main loop adds each step it resolves
        // completed or skipped. A skipped step counts because the work serving it is no longer
        // needed.
        Set<StepId> allCompleted = new HashSet<>(completedOverrides);

        // Pre-scan for steps already met by durable game state, so serves waivers work
        // regardless of guide order: an early gather step can serve a later quest step.
        // Only durable evidence qualifies (see #monotonicallyMet). A met transient condition
        // on a far-future step is usually coincidence, and latching it would waive every
        // sub-condition that serves it.
        if (state != null) {
            for (GuideStep step : guide.allSteps()) {
                if (!allCompleted.contains(step.getId())
                        && !suppressedAutoLatch.contains(step.getId())
                        && monotonicallyMet(step.getCompletion(), state)) {
                    allCompleted.add(step.getId());
                }
            }
        }

        // Backward walk to a fixed point: a step whose serves targets are all done is done
        // ("make a redberry pie" once "give pie to Thurgo" is complete).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (GuideStep step : guide.allSteps()) {
                if (allCompleted.contains(step.getId())
                        || suppressedAutoLatch.contains(step.getId())) {
                    continue;
                }
                if (!allServedStepsComplete(step.getCompletion(), allCompleted)) {
                    continue;
                }
                // Never back-fill a step whose own unmet reading proves the work is undone.
                if (unmetProvesIncomplete(step.getCompletion())) {
                    continue;
                }
                allCompleted.add(step.getId());
                changed = true;
            }
        }

        for (GuideStep step : guide.allSteps()) {
            StepId id = step.getId();
            StepStatus status;

            if (skipped.contains(id)) {
                status = StepStatus.SKIPPED;
            } else if (completedOverrides.contains(id)) {
                status = StepStatus.COMPLETED;
            } else if (allCompleted.contains(id) && !suppressedAutoLatch.contains(id)) {
                status = StepStatus.COMPLETED;
            } else {
                boolean met = conditionMet(step.getCompletion(), state, allCompleted);
                boolean prereqsMet = prerequisitesSatisfied(step, resolved);
                // A met transient condition (items, GP, location) counts only once the
                // prerequisites are complete; on a far-future step it is coincidence. Monotonic
                // facts complete regardless of position.
                boolean trusted = prereqsMet || isMonotonic(step.getCompletion());
                if (met && trusted && !suppressedAutoLatch.contains(id)) {
                    status = StepStatus.COMPLETED;
                } else if (prereqsMet && step.isBackground()) {
                    // An open-ended activity (contract grind, crop growth) must not take the
                    // active slot, or it hides every task meant to interleave with it. Checked
                    // before hasActive so it resolves the same whether or not a step is ACTIVE.
                    status = StepStatus.BACKGROUND;
                } else if (prereqsMet && !hasActive) {
                    status = StepStatus.ACTIVE;
                    hasActive = true;
                } else {
                    status = StepStatus.PENDING;
                }
            }

            if (status == StepStatus.COMPLETED || status == StepStatus.SKIPPED) {
                allCompleted.add(id);
            }

            resolved.put(id, status);
            results.add(new ResolvedStep(id, status));
        }

        return results;
    }

    /**
     * How many finished Slayer tasks the plugin has watched, for one master or for all.
     *
     * <p>A null master sums every master's tally. An unknown master name counts zero, which
     * strands the step rather than latching it.
     */
    static int countCompletedSlayerTasks(PlayerState state, String masterName) {
        Map<Integer, Integer> tallies = state.getSlayerTasksCompleted();
        if (masterName == null) {
            int total = 0;
            for (int n : tallies.values()) {
                total += n;
            }
            return total;
        }
        Integer masterId = SlayerMasters.valueFor(masterName);
        return masterId == null ? 0 : tallies.getOrDefault(masterId, 0);
    }

    /** Evaluate a condition with no completed steps for waivers. */
    public static boolean evaluateCondition(CompletionCondition condition, PlayerState state) {
        return conditionMet(condition, state, Collections.emptySet());
    }

    /** {@link #conditionMet} for callers outside the engine. */
    public static boolean evaluateCondition(
            CompletionCondition condition, PlayerState state, Set<StepId> completedSteps) {
        return conditionMet(condition, state, completedSteps);
    }

    static boolean conditionMet(CompletionCondition condition, PlayerState state) {
        return conditionMet(condition, state, Collections.emptySet());
    }

    /**
     * Whether the game state meets a condition, with a set of completed step IDs for
     * condition waiver.
     *
     * <p>In a composite of either mode, an arm whose served step is already completed is
     * waived (treated as met) even if the game state does not satisfy it, unless the arm's
     * unmet reading proves the work undone.
     */
    static boolean conditionMet(
            CompletionCondition condition, PlayerState state, Set<StepId> completedSteps) {
        if (condition instanceof SkillLevel) {
            SkillLevel c = (SkillLevel) condition;
            Skill skill = skillOrNull(c.getSkill());
            return skill != null && state.getSkillLevel(skill) >= c.getLevel();

        } else if (condition instanceof CompletionCondition.CombatLevel) {
            return state.getCombatLevel() >= ((CompletionCondition.CombatLevel) condition).getLevel();

        } else if (condition instanceof SkillXp) {
            SkillXp c = (SkillXp) condition;
            return xpReached(state, c.getSkill(), c.getMinXp());

        } else if (condition instanceof XpGained) {
            XpGained c = (XpGained) condition;
            return xpReached(state, c.getSkill(), c.getMinXp());

        } else if (condition instanceof QuestComplete) {
            return questStatus(state, ((QuestComplete) condition).getQuestName())
                    == QuestStatus.COMPLETED;

        } else if (condition instanceof GpThreshold) {
            GpThreshold c = (GpThreshold) condition;
            return countGpByLocation(state, c.getLocation()) >= c.getMinGp();

        } else if (condition instanceof HasItem) {
            HasItem c = (HasItem) condition;
            ItemLocation location = c.getLocation() != null ? c.getLocation() : ItemLocation.ANYWHERE;
            Set<Integer> ids = countedIds(c);
            int count = ids != null
                    ? countItemsByIds(state, ids, location)
                    : countItemByLocation(state, c.getItemId(), c.getItemName(), location);
            // Subtract quantities consumed by completed steps. A variable consumption (null
            // quantity) subtracts nothing, conservatively.
            int effectiveQuantity = c.getQuantity();
            for (CompletionCondition.ConsumedAtEntry entry : c.getConsumedAt()) {
                if (entry.getQuantity() != null && completedSteps.contains(new StepId(entry.getStepId()))) {
                    effectiveQuantity -= entry.getQuantity();
                }
            }
            return count >= Math.max(effectiveQuantity, 0);

        } else if (condition instanceof HasItemTotal) {
            HasItemTotal c = (HasItemTotal) condition;
            ItemLocation location = c.getLocation() != null ? c.getLocation() : ItemLocation.ANYWHERE;
            // The listed items are interchangeable, so only their combined total matters.
            Set<Integer> ids = new HashSet<>();
            for (CompletionCondition.ItemRef ref : c.getItems()) {
                if (ref.getItemId() != 0) {
                    ids.add(ref.getItemId());
                }
            }
            return countItemsByIds(state, ids, location) >= c.getQuantity();

        } else if (condition instanceof DiaryComplete) {
            DiaryComplete c = (DiaryComplete) condition;
            // Two sources, OR'd. The completion varbit works from login for a tier finished
            // before the plugin was watching; the one-shot chat announcement lands the moment
            // it fires, before the varbit is read back.
            Map<String, Boolean> tiers = state.getAchievementDiaries().get(c.getArea());
            boolean announced = tiers != null && Boolean.TRUE.equals(tiers.get(c.getTier()));
            return announced
                    || Boolean.TRUE.equals(DiaryVarbits.completed(c.getArea(), c.getTier(), state));

        } else if (condition instanceof DiaryReward) {
            // No chat fallback: the announcement marks the last task done, not the reward claimed.
            DiaryReward c = (DiaryReward) condition;
            return Boolean.TRUE.equals(DiaryVarbits.rewardClaimed(c.getArea(), c.getTier(), state));

        } else if (condition instanceof CombatAchievement) {
            Integer varbitId = ((CombatAchievement) condition).getVarbitId();
            return varbitId != null && state.getVarbits().getOrDefault(varbitId, 0) != 0;

        } else if (condition instanceof KillCount) {
            KillCount c = (KillCount) condition;
            return state.getKillCounts().getOrDefault(c.getTarget(), 0L) >= c.getCount();

        } else if (condition instanceof CollectionLog) {
            return state.getCollectionLog().contains(((CollectionLog) condition).getItemName());

        } else if (condition instanceof LocationReached) {
            Integer regionId = state.getRegionId();
            return regionId != null && regionId == ((LocationReached) condition).getRegionId();

        } else if (condition instanceof QuestStarted) {
            QuestStatus qs = questStatus(state, ((QuestStarted) condition).getQuestName());
            return qs == QuestStatus.IN_PROGRESS || qs == QuestStatus.COMPLETED;

        } else if (condition instanceof QuestProgress) {
            QuestProgress c = (QuestProgress) condition;
            return state.getQuestProgress().getOrDefault(normalizeQuestName(c.getQuestName()), 0)
                    >= c.getMinValue();

        } else if (condition instanceof VarbitValue) {
            VarbitValue c = (VarbitValue) condition;
            int current = state.getVarbits().getOrDefault(c.getVarbitId(), 0);
            // maxValue bounds categorical varbits (e.g. POH house location), where a higher
            // value is a different state, not more progress.
            return current >= c.getMinValue()
                    && (c.getMaxValue() == null || current <= c.getMaxValue());

        } else if (condition instanceof VarpBitValue) {
            VarpBitValue c = (VarpBitValue) condition;
            return ((state.getVarps().getOrDefault(c.getVarpId(), 0) >> c.getBit()) & 1) == 1;

        } else if (condition instanceof CompletionCondition.VarpValue) {
            CompletionCondition.VarpValue c = (CompletionCondition.VarpValue) condition;
            return state.getVarps().getOrDefault(c.getVarp(), 0) >= c.getMinValue();

        } else if (condition instanceof CompletionCondition.ObjectPresent) {
            // Any-of over the tier ids the condition enumerates (see
            // CompletionCondition.ObjectPresent). Read against objects ever SEEN, never the
            // current scene: the object leaves the scene when the player leaves the house.
            for (Integer objectId : ((CompletionCondition.ObjectPresent) condition).getObjectIds()) {
                if (state.getObservedObjects().contains(objectId)) {
                    return true;
                }
            }
            return false;

        } else if (condition instanceof CompletionCondition.SlayerTaskComplete) {
            CompletionCondition.SlayerTaskComplete c =
                    (CompletionCondition.SlayerTaskComplete) condition;
            // Read against tasks WATCHED finishing; see SlayerTaskTracker.
            return countCompletedSlayerTasks(state, c.getMaster()) >= c.getCount();

        } else if (condition instanceof CompletionCondition.NoSlayerTask) {
            // The login pass resolves before the first Slayer sample. The unread sentinel
            // (PlayerState.SLAYER_TASK_COUNT_UNREAD) is negative, so it never reads as zero.
            return state.getSlayerTaskCount() == 0;

        } else if (condition instanceof NpcInteraction) {
            // Answered from interactions the plugin watched: the game keeps no record of a
            // conversation. Used only for beats no var tracks, such as Fairytale I's gardeners.
            NpcInteraction c = (NpcInteraction) condition;
            return NpcInteractions.satisfiedBy(
                    state.getObservedNpcInteractions(), c.getNpcName(), c.getAction());

        } else if (condition instanceof CompletionCondition.BoatOwned) {
            return boatOwned((CompletionCondition.BoatOwned) condition, state);

        } else if (condition instanceof CompletionCondition.BoatComponent) {
            return boatComponentBuilt((CompletionCondition.BoatComponent) condition, state);

        } else if (condition instanceof CompletionCondition.BoatFacility) {
            return boatFacilityBuilt((CompletionCondition.BoatFacility) condition, state);

        } else if (condition instanceof Composite) {
            Composite c = (Composite) condition;
            // Only required conditions count; optional sub-goals are advisory.
            int metCount = 0;
            int requiredTotal = 0;

            Set<Integer> waivedIndices = buildWaivedIndices(c, completedSteps);

            for (int i = 0; i < c.getConditions().size(); i++) {
                CompletionCondition sub = c.getConditions().get(i);
                if (sub.isOptional()) {
                    continue;
                }
                requiredTotal++;
                if (waivedIndices.contains(i) || conditionMet(sub, state, completedSteps)) {
                    metCount++;
                }
            }
            // Vacuously true for ALL when every condition is optional (requiredTotal == 0).
            return c.getMode() == CompositeMode.ALL ? metCount == requiredTotal : metCount > 0;
        }

        // Manual, and any kind the plugin does not recognise.
        return false;
    }

    /**
     * Resolves a guide skill name, or null when the name is unknown. Callers treat an unknown
     * name as unmet or as no data: snapshot building runs on every game event, and a thrown
     * exception would take the panel down over one unrecognised skill name.
     */
    static Skill skillOrNull(String name) {
        try {
            return Skill.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The player's total XP in a skill; 0 when the skill is unread or the name unknown. */
    static long skillXp(PlayerState state, String skillName) {
        Skill skill = skillOrNull(skillName);
        SkillState ss = skill != null ? state.getSkills().get(skill) : null;
        return ss != null ? ss.getXp() : 0;
    }

    private static boolean xpReached(PlayerState state, String skillName, long minXp) {
        return skillOrNull(skillName) != null && skillXp(state, skillName) >= minXp;
    }

    /** The player's status for a quest named as the guide writes it; null when unread. */
    static QuestStatus questStatus(PlayerState state, String guideQuestName) {
        return state.getQuests().get(normalizeQuestName(guideQuestName));
    }

    /**
     * Match by id when {@code itemId} is non-zero (items like the three Silverlight keys share
     * a display name), otherwise by case-insensitive name.
     */
    private static boolean itemMatches(ItemStack item, int itemId, String itemName) {
        if (itemId != 0) {
            return item.getItemId() == itemId;
        }
        return itemName.equalsIgnoreCase(item.getName());
    }

    /**
     * Counts an item in the inventory or the bank, adding what its filled sacks and baskets
     * hold (see {@link ContainerType}).
     */
    static int countItemIn(List<ItemStack> items, int itemId, String itemName) {
        return countMatching(items, itemId, itemName) + countContainerContents(items, itemId);
    }

    /** Counts the stacks matching an item, without opening any container; 0 for null. */
    static int countMatching(Collection<ItemStack> items, int itemId, String itemName) {
        if (items == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : items) {
            if (itemMatches(item, itemId, itemName)) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    /** Count coins by location; a null location means anywhere. */
    static long countGpByLocation(PlayerState state, ItemLocation location) {
        long inventory = countItemIn(state.getInventory(), COINS_ITEM_ID, "Coins");
        long bank = countItemIn(state.getBank(), COINS_ITEM_ID, "Coins");
        if (location == ItemLocation.INVENTORY) {
            return inventory;
        }
        return location == ItemLocation.BANK ? bank : inventory + bank;
    }

    /** Count items matching any of the given ids at the given location. */
    private static int countItemsByIds(PlayerState state, Set<Integer> validIds, ItemLocation location) {
        int total = 0;
        if (location != ItemLocation.BANK) {
            total += countIds(state.getInventory(), validIds)
                    + countIds(state.getEquipment().values(), validIds);
        }
        if (location != ItemLocation.INVENTORY) {
            total += countIds(state.getBank(), validIds);
        }
        // Side containers for every location, shared with countItemByLocation; see
        // sideContainerCounts.
        for (int id : validIds) {
            total += sideContainerCounts(state, id, null).toward(location);
        }
        return total;
    }

    private static int countIds(Collection<ItemStack> items, Set<Integer> validIds) {
        int total = 0;
        for (ItemStack item : items) {
            if (validIds.contains(item.getItemId())) {
                total += item.getQuantity();
            }
        }
        return total;
    }

    private static final int COINS_ITEM_ID = 995;

    /**
     * One slice of a tool-leprechaun stored count: a varbit, and where its value sits in
     * the assembled total.
     *
     * <p>The game keeps ultracompost and plant cure in one varbit each, and splits every other
     * slot's count across two or three: a narrow low field in the farming varp, plus one or
     * two "extra" varbits holding the higher bits.
     */
    @Value
    private static class StoredSlice {
        int varbitId;
        /** Bit offset this slice occupies in the assembled total (0 for the low field). */
        int shift;
    }

    /**
     * Maps a farming item ID to the varbit slices holding how many of it the tool leprechaun
     * stores. Stored items are in neither inventory, bank nor equipment, so ANYWHERE ownership
     * checks fold this count in.
     *
     * <p>Varbit IDs are RuneLite's gameval FARMING_TOOLS_*. Each slice's shift is the width of
     * everything below it, and the slices compose to the caps the OSRS Wiki's Tool Leprechaun
     * page gives: 1,000 for supplies, 100 for tools. The comments below state the low field's
     * width, which equals the next slice's shift.
     */
    private static final Map<Integer, List<StoredSlice>> LEPRECHAUN_STORAGE_VARBITS =
            Map.ofEntries(
            // Supplies. Ultracompost and plant cure each have a single ten-bit varbit (0-1023).
            Map.entry(21483, List.of(new StoredSlice(5732, 0))),   // Ultracompost
            Map.entry(6036, List.of(new StoredSlice(6268, 0))),    // Plant cure
            Map.entry(6034, List.of(new StoredSlice(1443, 0),      // Supercompost, 8 bits
                    new StoredSlice(6267, 8))),
            Map.entry(6032, List.of(new StoredSlice(1442, 0),      // Compost, 8 bits
                    new StoredSlice(6266, 8))),
            // The bucket takes three slices: 5 bits, then 3, then 2.
            Map.entry(1925, List.of(new StoredSlice(1441, 0),      // Bucket
                    new StoredSlice(4731, 5),
                    new StoredSlice(6265, 8))),
            // Tools: the first varbit is one bit and the second adds the rest of a seven-bit count.
            Map.entry(952, List.of(new StoredSlice(1437, 0),       // Spade
                    new StoredSlice(8361, 1))),
            Map.entry(5343, List.of(new StoredSlice(1436, 0),      // Seed dibber
                    new StoredSlice(8358, 1))),
            Map.entry(5341, List.of(new StoredSlice(1435, 0),      // Rake
                    new StoredSlice(8357, 1))),
            Map.entry(5325, List.of(new StoredSlice(1440, 0),      // Gardening trowel
                    new StoredSlice(8360, 1)))
    );

    /**
     * Varbit slices holding the shared secateurs count. Plain and magic secateurs occupy
     * one storage slot between them, so the count alone cannot say which is in it.
     */
    private static final List<StoredSlice> SECATEURS_STORAGE_VARBITS =
            List.of(new StoredSlice(1438, 0), new StoredSlice(8359, 1));

    /**
     * Varbit flagging which secateurs fill the shared slot: 1 for magic secateurs, 0 for
     * plain. Magic secateurs raise the chance of extra yield by 10% on herbs, allotments,
     * bushes and more, so stored plain ones must not satisfy a magic-secateurs check.
     */
    private static final int SECATEURS_TYPE_VARBIT = 1848;

    private static final int SECATEURS_ITEM_ID = 5329;
    private static final int MAGIC_SECATEURS_ITEM_ID = 7409;

    /**
     * Varbit naming which watering can the leprechaun is holding, or 0 for none.
     *
     * <p>Not a count: only one can is stored, and the slot records <em>which</em> can. The
     * value is the water level plus one: 1 for an empty {@code Watering can}, 2 through 9 for
     * {@code Watering can(1)} through {@code Watering can(8)}. One full can deposited reads 9.
     */
    private static final int WATERING_CAN_STORAGE_VARBIT = VarbitID.FARMING_TOOLS_WATERINGCAN;

    /**
     * Watering can item IDs indexed by water level, empty first. The index into this array
     * plus one is the value {@link #WATERING_CAN_STORAGE_VARBIT} holds when that can is the
     * stored one. Spelled out because the id run has a gap at 5332.
     */
    private static final int[] WATERING_CAN_ITEM_IDS = {
            ItemID.WATERING_CAN_0, ItemID.WATERING_CAN_1, ItemID.WATERING_CAN_2,
            ItemID.WATERING_CAN_3, ItemID.WATERING_CAN_4, ItemID.WATERING_CAN_5,
            ItemID.WATERING_CAN_6, ItemID.WATERING_CAN_7, ItemID.WATERING_CAN_8,
    };

    /** The stored-can value that would name this item, or 0 for anything else. */
    private static int wateringCanStorageValue(int itemId) {
        for (int level = 0; level < WATERING_CAN_ITEM_IDS.length; level++) {
            if (WATERING_CAN_ITEM_IDS[level] == itemId) {
                return level + 1;
            }
        }
        return 0;
    }

    /**
     * Varbit IDs backing tool-leprechaun storage, scanned at login so stored items count
     * before the next deposit changes a varbit.
     *
     * <p>Declared below every table it is collected from, because a static field initialised
     * from a later one reads null.
     */
    public static final Set<Integer> LEPRECHAUN_STORAGE_VARBIT_IDS = collectLeprechaunVarbitIds();

    private static Set<Integer> collectLeprechaunVarbitIds() {
        Set<Integer> ids = new HashSet<>();
        for (List<StoredSlice> slices : LEPRECHAUN_STORAGE_VARBITS.values()) {
            for (StoredSlice slice : slices) {
                ids.add(slice.getVarbitId());
            }
        }
        for (StoredSlice slice : SECATEURS_STORAGE_VARBITS) {
            ids.add(slice.getVarbitId());
        }
        ids.add(SECATEURS_TYPE_VARBIT);
        ids.add(WATERING_CAN_STORAGE_VARBIT);
        return Set.copyOf(ids);
    }

    /**
     * Quantity of a farming item the tool leprechaun is holding for the player, or 0 for
     * items the leprechaun does not store. Counts only toward ANYWHERE, never INVENTORY or
     * BANK.
     *
     * <p>Storage is per account: every leprechaun opens the same store.
     */
    static int leprechaunStorageQuantity(PlayerState state, int itemId) {
        if (itemId == SECATEURS_ITEM_ID || itemId == MAGIC_SECATEURS_ITEM_ID) {
            boolean magicStored = varbitValue(state, SECATEURS_TYPE_VARBIT) == 1;
            if (magicStored != (itemId == MAGIC_SECATEURS_ITEM_ID)) {
                return 0;
            }
            return assemble(state, SECATEURS_STORAGE_VARBITS);
        }
        int canValue = wateringCanStorageValue(itemId);
        if (canValue != 0) {
            // Only the can whose water level the slot names.
            return varbitValue(state, WATERING_CAN_STORAGE_VARBIT) == canValue ? 1 : 0;
        }
        List<StoredSlice> slices = LEPRECHAUN_STORAGE_VARBITS.get(itemId);
        return slices == null ? 0 : assemble(state, slices);
    }

    private static int assemble(PlayerState state, List<StoredSlice> slices) {
        int total = 0;
        for (StoredSlice slice : slices) {
            total += varbitValue(state, slice.getVarbitId()) << slice.getShift();
        }
        return total;
    }

    /** A varbit as a count: unread (absent from {@link PlayerState}) and negative read as zero. */
    private static int varbitValue(PlayerState state, int varbitId) {
        Integer value = state.getVarbits().get(varbitId);
        return (value != null && value > 0) ? value : 0;
    }

    /**
     * Maps a base item to its filled sack or basket variants, whose contents add to the loose
     * count ("Potatoes(10)" contributes 10 potatoes).
     *
     * <p>Variant IDs step by 2 from the container base:
     * {@code containerBaseId + (count - 1) * 2 = variantId}
     */
    @Value
    private static class ContainerType {
        int baseItemId;
        int containerBaseId;
        int maxCount;
    }

    private static final List<ContainerType> CONTAINER_TYPES = List.of(
            // Sacks (capacity 10)
            new ContainerType(1942, 5420, 10),  // Potato  → Potatoes(1..10)
            new ContainerType(1957, 5440, 10),  // Onion   → Onions(1..10)
            new ContainerType(1965, 5460, 10),  // Cabbage → Cabbages(1..10)
            // Baskets (capacity 5)
            new ContainerType(1955, 5378, 5),   // Cooking apple → Apples(1..5)
            new ContainerType(2108, 5388, 5),   // Orange        → Oranges(1..5)
            new ContainerType(5504, 5398, 5),   // Strawberry    → Strawberries(1..5)
            new ContainerType(1963, 5408, 5),   // Banana        → Bananas(1..5)
            new ContainerType(1982, 5960, 5)    // Tomato        → Tomatoes(1..5)
    );

    /** Count the given base item inside filled sacks and baskets; see {@link ContainerType}. */
    private static int countContainerContents(List<ItemStack> items, int itemId) {
        if (itemId == 0) {
            return 0;
        }
        int total = 0;
        for (ContainerType ct : CONTAINER_TYPES) {
            if (ct.baseItemId != itemId) {
                continue;
            }
            for (ItemStack item : items) {
                int id = item.getItemId();
                if (id >= ct.containerBaseId && id <= ct.containerBaseId + (ct.maxCount - 1) * 2) {
                    int offset = id - ct.containerBaseId;
                    if (offset % 2 == 0) {
                        int fillLevel = offset / 2 + 1;
                        total += fillLevel * item.getQuantity();
                    }
                }
            }
            break;
        }
        return total;
    }

    /**
     * How many of an item the player owns anywhere they can retrieve it from — inventory,
     * equipment, bank, rune pouch, seed box, plank sack, bow string spool, tackle box, seed
     * vault, the tool leprechaun, or the cargo hold of a boat still owned.
     *
     * <p>Shares the {@code hasItem} code path, so the two agree on what counts as owned.
     */
    public static int countOwned(PlayerState state, int itemId, String itemName) {
        if (state == null) {
            return 0;
        }
        // Id 0 matches by name, so a null name would throw inside the scan.
        if (itemId == 0 && itemName == null) {
            return 0;
        }
        return countItemByLocation(state, itemId, itemName, ItemLocation.ANYWHERE);
    }

    /**
     * How many of an item the player has <em>on them</em> right now — inventory, worn, or in
     * a carried container such as the rune pouch or plank sack — as opposed to owning
     * something they would have to fetch.
     */
    public static int countCarried(PlayerState state, int itemId, String itemName) {
        if (state == null || (itemId == 0 && itemName == null)) {
            return 0;
        }
        return countItemByLocation(state, itemId, itemName, ItemLocation.INVENTORY);
    }

    private static int countItemByLocation(PlayerState state, int itemId, String itemName, ItemLocation location) {
        int total = sideContainerCounts(state, itemId, itemName).toward(location);
        if (location != ItemLocation.BANK) {
            total += countItemIn(state.getInventory(), itemId, itemName)
                    + countMatching(state.getEquipment().values(), itemId, itemName);
        }
        if (location != ItemLocation.INVENTORY) {
            total += countItemIn(state.getBank(), itemId, itemName);
        }
        return total;
    }

    /**
     * What every container outside the inventory, equipment and bank arrays holds of one
     * item, split by where the count belongs.
     *
     * <p><b>The single place the container list lives.</b> The id+name count path, the id-set
     * path behind item classes and {@code hasItemTotal}, and the side panel's progress bar all
     * read it, so a container listed here reaches all three. Whether an item has an
     * {@code item_classes.json} entry picks its count path, so both paths sum this method and
     * an item counts the same whichever path asks.
     *
     * <p>The rune pouch, the seed box, the plank sack and the bow string spool <em>travel</em>:
     * their contents count as carried or banked, wherever the container is. The seed vault,
     * the tool leprechaun and the boats' cargo holds are storage the player must walk to, so
     * their contents count toward ownership only. So do the tackle box's: it travels, but an
     * item must come out of it for most uses (see {@link SeedBox}).
     *
     * <p>The buckets are exclusive: each container's contents land in one bucket, so an
     * ANYWHERE total never double-counts.
     */
    static SideContainerCounts sideContainerCounts(PlayerState state, int itemId, String itemName) {
        // Id 0 matches by name, and the id-set callers pass no name.
        if (state == null || (itemId == 0 && itemName == null)) {
            return SideContainerCounts.NONE;
        }
        int carried = 0;
        int banked = 0;
        int stored = 0;

        int pouched = itemId == 0 ? 0 : Math.max(state.getRunePouch().getOrDefault(itemId, 0), 0);
        if (pouched > 0) {
            if (HeldItems.carried(state, RunePouch.POUCH_ITEM_IDS)) {
                carried += pouched;
            } else if (HeldItems.banked(state, RunePouch.POUCH_ITEM_IDS)) {
                banked += pouched;
            }
        }

        // An untrusted seed box tally answers ownership but not "is it on me" (see
        // SeedBox#contentsCarried). Under-counting carried seeds costs a needless withdrawal;
        // over-counting sends the player off without seeds to plant.
        int boxed = countMatching(state.getSeedBox(), itemId, itemName);
        if (boxed > 0) {
            if (SeedBox.contentsCarried(state)) {
                carried += boxed;
            } else if (SeedBox.banked(state)) {
                banked += boxed;
            } else if (SeedBox.carried(state) || state.getBankLastSeen() == null) {
                // Owned, whereabouts unsettled: an unread box in the bag, or a bank never seen.
                // A box absent from both the inventory and a seen bank is gone, seeds and all.
                stored += boxed;
            }
        }

        // The plank sack's contents are always-current varbits, so like the pouch it counts
        // only where the sack is seen. Under-counting leaves a step active; over-counting
        // would latch an unfinished one.
        int sacked = PlankSack.stored(state, itemId);
        if (sacked > 0) {
            if (PlankSack.carried(state)) {
                carried += sacked;
            } else if (PlankSack.banked(state)) {
                banked += sacked;
            }
        }

        // The spool's count is bound to the account and survives losing the spool, so the
        // location gate stops a player with no spool reading as string-rich.
        int spooled = BowStringSpool.stored(state, itemId);
        if (spooled > 0) {
            if (BowStringSpool.carried(state)) {
                carried += spooled;
            } else if (BowStringSpool.banked(state)) {
                banked += spooled;
            }
        }

        // Owned but not to hand: storage the player travels to, and the tackle box.
        stored += countMatching(state.getTackleBox(), itemId, itemName)
                + countMatching(state.getSeedVault(), itemId, itemName)
                + leprechaunStorageQuantity(state, itemId);

        // A cargo hold counts only while its boat slot is still owned: a scrapped boat takes
        // its stores with it.
        if (!state.getCargoHolds().isEmpty()) {
            for (int slot = 0; slot < BoatVarbits.SLOTS; slot++) {
                if (varbitValue(state, BoatVarbits.owned(slot)) > 0) {
                    stored += countMatching(state.getCargoHold(slot), itemId, itemName);
                }
            }
        }

        return SideContainerCounts.of(carried, banked, stored);
    }

    /**
     * One item's holdings across every container that is not the inventory, the equipment or
     * the bank, split into the three buckets {@link #sideContainerCounts} sorts them into.
     *
     * <p>Every {@code hasItem} resolution builds one, so the common empty case returns the
     * shared {@link #NONE} without allocating.
     */
    @Value
    static class SideContainerCounts {
        /** Held in a container that travels, and that container is in the inventory. */
        int carried;
        /** Held in a container that travels, and that container is in the bank. */
        int banked;
        /** Held in storage the player owns and must travel to before use. */
        int stored;

        static final SideContainerCounts NONE = new SideContainerCounts(0, 0, 0);

        static SideContainerCounts of(int carried, int banked, int stored) {
            return (carried | banked | stored) == 0
                    ? NONE
                    : new SideContainerCounts(carried, banked, stored);
        }

        /** How much of this counts toward a check scoped to {@code location}. */
        int toward(ItemLocation location) {
            switch (location) {
                case INVENTORY:
                    return carried;
                case BANK:
                    return banked;
                case ANYWHERE:
                default:
                    return carried + banked + stored;
            }
        }
    }

    /**
     * Returns true if the condition tracks a monotonic game fact: state that never regresses
     * once reached (quest stages, diary bits, skill levels, collection log slots). A met
     * monotonic condition is evidence the step happened wherever the player is in the guide.
     *
     * <p>Transient conditions (items, GP, location, kill counts) can read true by coincidence,
     * so they are trusted only at the prerequisite frontier. A bounded varbit check is
     * categorical (spellbook, POH location), and a VarpValue can read a varp that falls back,
     * as a bird house space's resets to 0 on every collection, so both are transient.
     *
     * <p>A composite is monotonic only if every non-optional sub-condition is; one with none
     * is vacuously met and never trusted at a distance.
     *
     * <p>Deliberately mode-blind: callers trust a met reading of the whole condition, and an
     * {@code any} composite can read met through a transient arm alone. Reading only the
     * durable arms of an {@code any} goes through {@link #monotonicallyMet}.
     */
    static boolean isMonotonic(CompletionCondition condition) {
        if (condition instanceof QuestComplete
                || condition instanceof QuestStarted
                || condition instanceof QuestProgress
                || condition instanceof DiaryComplete
                // The taskmaster sets the claim flag once and the game never clears it.
                || condition instanceof DiaryReward
                || condition instanceof CombatAchievement
                || condition instanceof CollectionLog
                || condition instanceof SkillLevel
                || condition instanceof CompletionCondition.CombatLevel
                || condition instanceof SkillXp
                || condition instanceof XpGained
                || condition instanceof VarpBitValue
                || condition instanceof CompletionCondition.BoatOwned
                || condition instanceof CompletionCondition.BoatComponent
                || condition instanceof CompletionCondition.BoatFacility
                // The record of a sighting persists after the object leaves the scene.
                || condition instanceof CompletionCondition.ObjectPresent
                // The finished-task tally only grows, so the next task cannot undo the step.
                || condition instanceof CompletionCondition.SlayerTaskComplete) {
            return true;
        }
        if (condition instanceof VarbitValue) {
            // An open-ended threshold reads a flag or counter that only grows; a bounded one
            // reads a categorical value, which can change back.
            return ((VarbitValue) condition).getMaxValue() == null;
        }
        if (condition instanceof Composite) {
            boolean anyRequired = false;
            for (CompletionCondition sub : ((Composite) condition).getConditions()) {
                if (sub.isOptional()) {
                    continue;
                }
                anyRequired = true;
                if (!isMonotonic(sub)) {
                    return false;
                }
            }
            return anyRequired;
        }
        return false;
    }

    /**
     * Returns true if an <em>unmet</em> reading proves the step was not performed. Such steps
     * are refused serves-completion, which would otherwise mark, say, an in-progress quest
     * complete because a later diary bit it serves is set.
     *
     * <p>A strict subset of {@link #isMonotonic}. {@code ObjectPresent} and
     * {@code SlayerTaskComplete} never regress, but they are observed only while the plugin
     * watches: no sighting or a zero tally does not prove the work was never done (it may
     * predate the install). Serves-completion recovers exactly those players, through the
     * serves edges the guide lists on object steps, so both stay out of this predicate.
     * {@code CollectionLog} is out for the same reason: the plugin fills it only from the
     * chat announcements it has seen. So are a {@code CombatAchievement} with no varbit id and
     * a {@code DiaryReward} whose area and tier name no claim flag: both read false whatever
     * the player has done.
     *
     * <p>Composites recurse rather than delegate to {@link #isMonotonic}, so a composite made
     * purely of object arms ("this object or that one") stays recoverable too.
     */
    static boolean unmetProvesIncomplete(CompletionCondition condition) {
        if (condition instanceof CompletionCondition.ObjectPresent
                || condition instanceof CompletionCondition.SlayerTaskComplete
                || condition instanceof CollectionLog) {
            return false;
        }
        if (condition instanceof CombatAchievement
                && ((CombatAchievement) condition).getVarbitId() == null) {
            return false;
        }
        if (condition instanceof DiaryReward) {
            DiaryReward reward = (DiaryReward) condition;
            return DiaryVarbits.rewardVarbitFor(reward.getArea(), reward.getTier()) != null;
        }
        if (condition instanceof Composite) {
            boolean anyRequired = false;
            for (CompletionCondition sub : ((Composite) condition).getConditions()) {
                if (sub.isOptional()) {
                    continue;
                }
                anyRequired = true;
                if (!unmetProvesIncomplete(sub)) {
                    return false;
                }
            }
            return anyRequired;
        }
        return isMonotonic(condition);
    }

    /**
     * Returns true if the condition can be read as durable evidence during the
     * login/reset pre-scan, which is a weaker bar than {@link #isMonotonic}.
     *
     * <p>An {@code any} composite is satisfied by one arm, so one durable arm makes the step
     * provable (a finished quest beside a bounded growth varbit). {@link #isMonotonic} demands
     * every arm be durable and would strand such a step.
     *
     * <p>Eligibility is not permission to latch: the composite must be read through its
     * durable arms only. {@link #monotonicallyMet} does that and calls this predicate itself.
     *
     * <p>Only a top-level {@code any} is widened. An {@code any} nested inside an {@code all}
     * stays ineligible, which strands rather than falsely latches.
     *
     * <p>A missing or unrecognised mode reads as {@code any}, matching
     * {@link #conditionMet}, so the strict branch keys on {@code all}.
     */
    static boolean preScanEligible(CompletionCondition condition) {
        if (condition instanceof Composite) {
            Composite c = (Composite) condition;
            if (c.getMode() == CompositeMode.ALL) {
                return isMonotonic(condition);
            }
            for (CompletionCondition sub : c.getConditions()) {
                if (!sub.isOptional() && preScanEligible(sub)) {
                    return true;
                }
            }
            return false;
        }
        return isMonotonic(condition);
    }

    /**
     * Evaluate a condition using only the evidence that proves the step was performed, so it
     * can be trusted at any distance from the prerequisite frontier. Used by the login/reset
     * pre-scan in {@link #resolveSteps} and by the matching auto-latch/suppression-clear
     * check in {@code CoreEngine}, which must agree on what latches at a distance.
     *
     * <p>Anything {@link #preScanEligible} rejects returns false, so a bare {@code hasItem} can
     * never latch here. An {@code any} composite is read through its durable arms only.
     *
     * <p>Evaluation is waiver-free: a serves waiver is evidence about a downstream step, not
     * about this one.
     *
     * @param state current player state; a null state proves nothing, so returns false
     */
    static boolean monotonicallyMet(CompletionCondition condition, PlayerState state) {
        if (state == null || !preScanEligible(condition)) {
            return false;
        }
        if (condition instanceof Composite) {
            Composite c = (Composite) condition;
            if (c.getMode() != CompositeMode.ALL) {
                // Recursion re-checks eligibility per arm, so transient arms read false.
                for (CompletionCondition sub : c.getConditions()) {
                    if (!sub.isOptional() && monotonicallyMet(sub, state)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return conditionMet(condition, state);
    }

    /**
     * Check whether a step's condition has serves edges and all of them are satisfied: for a
     * composite, every sub-condition with serves edges has at least one served step complete.
     * Returns false if no serves edges exist.
     */
    static boolean allServedStepsComplete(CompletionCondition condition, Set<StepId> completedSteps) {
        if (completedSteps.isEmpty()) {
            return false;
        }
        if (condition instanceof Composite) {
            Composite c = (Composite) condition;
            boolean anyServes = false;
            for (CompletionCondition sub : c.getConditions()) {
                if (hasServes(sub)) {
                    // An authoritative sub-condition must be genuinely met; leave the
                    // composite to real evaluation.
                    if (unmetProvesIncomplete(sub) || !anyServedComplete(sub, completedSteps)) {
                        return false;
                    }
                    anyServes = true;
                } else if (c.getMode() == CompositeMode.ALL) {
                    // An ALL sub-condition without serves edges must actually be satisfied.
                    return false;
                }
            }
            if (hasServes(condition)) {
                return anyServedComplete(condition, completedSteps);
            }
            return anyServes;
        }
        return anyServedComplete(condition, completedSteps);
    }

    private static boolean hasServes(CompletionCondition condition) {
        List<StepId> serves = condition.getServesSteps();
        return serves != null && !serves.isEmpty();
    }

    /** Whether any step the condition serves is complete; false when it serves none. */
    private static boolean anyServedComplete(CompletionCondition condition, Set<StepId> completedSteps) {
        List<StepId> serves = condition.getServesSteps();
        if (serves != null) {
            for (StepId served : serves) {
                if (completedSteps.contains(served)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Indices of composite sub-conditions waived because a step they serve is complete.
     * {@link #unmetProvesIncomplete} sub-conditions are never waived.
     */
    static Set<Integer> buildWaivedIndices(Composite composite, Set<StepId> completedSteps) {
        if (completedSteps.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Integer> waived = new HashSet<>();
        for (int i = 0; i < composite.getConditions().size(); i++) {
            CompletionCondition sub = composite.getConditions().get(i);
            if (anyServedComplete(sub, completedSteps) && !unmetProvesIncomplete(sub)) {
                waived.add(i);
            }
        }
        return waived;
    }

    private static boolean prerequisitesSatisfied(GuideStep step, Map<StepId, StepStatus> resolved) {
        if (step.getPrerequisites() == null || step.getPrerequisites().isEmpty()) {
            return true;
        }
        for (StepId prereq : step.getPrerequisites()) {
            StepStatus status = resolved.get(prereq);
            if (status != StepStatus.COMPLETED && status != StepStatus.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    // ---- Boat conditions ---------------------------------------------------------------
    //
    // The per-slot varbit tables live in BoatVarbits, shared with RequiredVarIds so the
    // login scan reads every id used here.

    /**
     * Finds the slot holding an owned boat of exactly the given type.
     *
     * Exact match on purpose: the route keeps upgrading the skiff after the sloop exists, so a
     * higher-tier boat must never stand in for a lower one.
     *
     * @return the 0-based slot index, or -1 when no owned slot holds that boat type
     */
    private static int findBoatSlot(PlayerState state, int boatType) {
        for (int i = 0; i < BoatVarbits.SLOTS; i++) {
            if (varbitValue(state, BoatVarbits.owned(i)) > 0
                    && varbitValue(state, BoatVarbits.type(i)) == boatType) {
                return i;
            }
        }
        return -1;
    }

    private static boolean boatOwned(CompletionCondition.BoatOwned c, PlayerState state) {
        int boatType = BoatVarbits.typeValue(c.getBoatType());
        return boatType >= 0 && findBoatSlot(state, boatType) >= 0;
    }

    private static boolean boatComponentBuilt(CompletionCondition.BoatComponent c, PlayerState state) {
        int boatType = BoatVarbits.typeValue(c.getBoatType());
        if (boatType < 0 || !BoatVarbits.isComponent(c.getComponent())) {
            return false;
        }
        int slot = findBoatSlot(state, boatType);
        return slot >= 0
                && varbitValue(state, BoatVarbits.componentVarbit(c.getComponent(), slot)) >= c.getMinTier();
    }

    private static boolean boatFacilityBuilt(CompletionCondition.BoatFacility c, PlayerState state) {
        int boatType = BoatVarbits.typeValue(c.getBoatType());
        if (boatType < 0 || !BoatVarbits.isFacility(c.getFacility(), boatType)) {
            return false;
        }
        int slot = findBoatSlot(state, boatType);
        int expectedValue = BoatVarbits.facilityBuiltValue(c.getFacility());
        if (slot < 0 || expectedValue < 0) {
            return false;
        }
        if (BoatVarbits.INOCULATION_STATION.equals(BoatVarbits.canonicalFacilityName(c.getFacility()))) {
            int hotspot = BoatVarbits.facilityVarbit(c.getFacility(), boatType, slot);
            return varbitValue(state, hotspot) == expectedValue;
        }
        for (int hotspot : BoatVarbits.hotspotsForSlot(slot)) {
            if (varbitValue(state, hotspot) == expectedValue) {
                return true;
            }
        }
        return false;
    }
}
