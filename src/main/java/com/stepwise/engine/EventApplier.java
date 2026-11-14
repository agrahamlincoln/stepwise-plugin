package com.stepwise.engine;

import com.stepwise.model.EquipmentSlot;
import com.stepwise.model.ItemStack;
import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import com.stepwise.model.Skill;
import com.stepwise.model.SkillState;
import lombok.Value;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The {@link PlayerState} updates that take more than a setter call. */
public final class EventApplier {

    private EventApplier() {
    }

    @Value
    public static class SkillSnapshot {
        int level;
        long xp;
    }

    public static void applyStatChanged(PlayerState state, String skillName, long xp, int level) {
        putSkill(state, skillName, level, xp);
        state.recalculateTotalLevel();
        state.recalculateCombatLevel();
    }

    /** Records one skill reading. A name {@link Skill} does not know is ignored. */
    private static void putSkill(PlayerState state, String skillName, int level, long xp) {
        try {
            state.getSkills().put(Skill.fromString(skillName), new SkillState(level, xp));
        } catch (IllegalArgumentException e) {
            // Not a skill the guide tracks.
        }
    }

    public static void applyLoginState(
            PlayerState state,
            Map<String, SkillSnapshot> skills,
            Integer questPoints,
            List<String> completedQuests,
            List<String> startedQuests,
            List<ItemStack> inventory,
            Map<EquipmentSlot, ItemStack> equipment) {

        if (skills != null) {
            for (Map.Entry<String, SkillSnapshot> entry : skills.entrySet()) {
                putSkill(state, entry.getKey(), entry.getValue().getLevel(), entry.getValue().getXp());
            }
            state.recalculateTotalLevel();
            state.recalculateCombatLevel();
        }

        if (questPoints != null) {
            state.setQuestPoints(questPoints);
        }

        if (completedQuests != null) {
            for (String quest : completedQuests) {
                state.getQuests().put(quest, QuestStatus.COMPLETED);
            }
        }

        if (startedQuests != null) {
            for (String quest : startedQuests) {
                state.getQuests().put(quest, QuestStatus.IN_PROGRESS);
            }
        }

        if (inventory != null) {
            state.setInventory(inventory);
        }

        if (equipment != null) {
            replaceEquipment(state, equipment);
        }
    }

    static void replaceEquipment(PlayerState state, Map<EquipmentSlot, ItemStack> equipment) {
        state.getEquipment().clear();
        state.getEquipment().putAll(equipment);
    }

    /**
     * Replace the tracked seed box contents and stamp them as verified.
     *
     * <p>Fed by the chat listing from checking the box: an empty box reads as an empty list,
     * a full one as the seeds it names. The listing is a whole-box statement, so it replaces
     * the tally rather than adjusting it. This is the only way an over-count comes down.
     */
    public static void applySeedBoxChanged(PlayerState state, List<ItemStack> items) {
        state.setSeedBox(items);
        // The only place verifiedAt is stamped: every other seed-box path adjusts a tally.
        state.setSeedBoxVerifiedAt(Instant.now());
    }

    /**
     * Adds seeds the player has just put in the box.
     *
     * <p>A running tally, unlike every other container here, because the game does not send
     * the seed box's contents as an item container. Seeds boxed before the plugin watched are
     * invisible and a missed movement stays missed until a Check listing replaces the tally,
     * so the tally drifts both ways.
     *
     * <p>{@link #applySeedBoxTaken} clamps at zero, which clears an under-count, but an
     * over-count survives every empty. Only {@link #applySeedBoxChanged} overwrites the whole
     * box.
     */
    public static void applySeedBoxStored(PlayerState state, ItemStack stored) {
        List<ItemStack> box = new ArrayList<>(state.getSeedBox());
        for (int i = 0; i < box.size(); i++) {
            ItemStack held = box.get(i);
            if (held.getItemId() == stored.getItemId()) {
                box.set(i, new ItemStack(held.getItemId(),
                        held.getQuantity() + stored.getQuantity(), held.getName()));
                commitSeedBox(state, box);
                return;
            }
        }
        box.add(stored);
        commitSeedBox(state, box);
    }

    /**
     * Removes seeds that have left the box, whether emptied into the bag or planted.
     *
     * <p>Matched by name, only against what the box holds. The chat line ("Emptied 1 x
     * Asgarnian seed to your inventory") does not name its container, so a name the box does
     * not hold is ignored rather than reported: the line came from another container.
     *
     * @param itemName as the game said it, which for a seed planted straight from the box is
     *                 lower-case, singular or plural — see {@link #namesTheSameItem}
     * @param quantity how many left, as a positive number
     */
    public static void applySeedBoxTaken(PlayerState state, String itemName, int quantity) {
        List<ItemStack> box = new ArrayList<>(state.getSeedBox());
        for (int i = 0; i < box.size(); i++) {
            ItemStack held = box.get(i);
            if (!namesTheSameItem(held.getName(), itemName)) {
                continue;
            }
            int left = held.getQuantity() - quantity;
            if (left > 0) {
                box.set(i, new ItemStack(held.getItemId(), left, held.getName()));
            } else {
                // Clamp at empty: seeds boxed before the plugin watched can make more leave
                // than the tally knew went in.
                box.remove(i);
            }
            commitSeedBox(state, box);
            return;
        }
    }

    /**
     * Case-insensitive name match with an optional trailing "s": the box says "Potato cactus
     * seed" when filled and "3 potato cactus seeds were taken" when planted from.
     */
    private static boolean namesTheSameItem(String held, String spoken) {
        if (held == null || spoken == null) {
            return false;
        }
        if (held.equalsIgnoreCase(spoken)) {
            return true;
        }
        return spoken.length() > 1
                && (spoken.endsWith("s") || spoken.endsWith("S"))
                && held.equalsIgnoreCase(spoken.substring(0, spoken.length() - 1));
    }

    private static void commitSeedBox(PlayerState state, List<ItemStack> box) {
        state.setSeedBox(box);
        // Lets seeds the player has just boxed count as carried rather than as a withdrawal.
        // See PlayerState.seedBoxWatchedAt for why this is separate from verifiedAt.
        state.setSeedBoxWatchedAt(Instant.now());
    }

    /**
     * Merges deposit-box deposits into the existing bank state. A deposit box does not show
     * the bank, so the items that left the inventory are added to the last known bank. The
     * next real bank open corrects it.
     */
    public static void applyDepositInference(PlayerState state, List<ItemStack> depositedItems) {
        List<ItemStack> bank = new ArrayList<>(state.getBank());

        for (ItemStack deposited : depositedItems) {
            boolean merged = false;
            for (int i = 0; i < bank.size(); i++) {
                if (deposited.getName().equalsIgnoreCase(bank.get(i).getName())) {
                    ItemStack existing = bank.get(i);
                    bank.set(i, new ItemStack(
                            existing.getItemId(),
                            existing.getQuantity() + deposited.getQuantity(),
                            existing.getName()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                bank.add(deposited);
            }
        }

        state.setBank(bank);
    }

    public static void applyDiaryCompleted(PlayerState state, String area, String difficulty) {
        state.getAchievementDiaries().computeIfAbsent(area, k -> new HashMap<>()).put(difficulty, true);
    }

    /**
     * @param questState as RuneLite's {@code QuestState} names it: {@code IN_PROGRESS} and
     *                   {@code FINISHED} move the quest's status, anything else leaves it
     */
    public static void applyQuestProgress(
            PlayerState state,
            String questName,
            String questState,
            Integer newValue) {

        if ("IN_PROGRESS".equals(questState)) {
            QuestStatus current = state.getQuests().get(questName);
            if (current == null || current == QuestStatus.NOT_STARTED) {
                state.getQuests().put(questName, QuestStatus.IN_PROGRESS);
            }
        } else if ("FINISHED".equals(questState)) {
            state.getQuests().put(questName, QuestStatus.COMPLETED);
        }

        if (newValue != null) {
            state.getQuestProgress().put(questName, newValue);
        }
    }
}
