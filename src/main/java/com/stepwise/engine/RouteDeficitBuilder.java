package com.stepwise.engine;

import com.stepwise.diary.DiaryVarbits;
import com.stepwise.guide.CompletionCondition;
import com.stepwise.guide.CompositeMode;
import com.stepwise.guide.GuideBudget;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.ItemLocation;
import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import com.stepwise.model.Skill;
import com.stepwise.snapshot.RouteDeficit;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects the budget ledger against live player state to produce a {@link RouteDeficit}.
 *
 * <p>Recomputed from {@code guide_budget.json} and the current {@link PlayerState} on every
 * snapshot.
 *
 * <p>Advisory only: it never completes, skips or fast-forwards anything, and deliberately does
 * not feed {@code ResolutionEngine.isMonotonic}. Holding the money a far-future step wants is
 * usually coincidence, not evidence the player has been there.
 */
public final class RouteDeficitBuilder {

    /** Hitpoints is the one skill that does not start at level 1. */
    private static final int STARTING_HITPOINTS = 10;

    private RouteDeficitBuilder() {
    }

    /**
     * Compare the player against what the route assumes on <em>arriving</em> at {@code entryId}.
     *
     * @return null when there is no budget loaded, no current entry, or no ledger row for the
     *         entry
     */
    public static RouteDeficit build(GuideBudget budget, String entryId, PlayerState state) {
        if (budget == null || entryId == null || state == null) {
            return null;
        }
        int idx = budget.indexOf(entryId);
        if (idx < 0) {
            return null;
        }
        GuideBudget.Entry entry = budget.entryAt(idx);

        // Compare against the previous entry's closing profile. This entry's own closing profile
        // would report a deficit for every player still standing on it.
        Map<String, Integer> openingLevels = idx > 0
                ? budget.entryAt(idx - 1).getLevelsAfter()
                : startingLevels();

        // Until a bank has been seen the cached bank reads empty, so GP is unknown.
        boolean gpKnown = state.getBankLastSeen() != null;
        long playerGp = gpKnown
                ? ResolutionEngine.countGpByLocation(state, ItemLocation.ANYWHERE)
                : 0L;

        List<RouteDeficit.SkillDelta> skills = new ArrayList<>();
        if (openingLevels != null) {
            for (Map.Entry<String, Integer> e : openingLevels.entrySet()) {
                Skill skill = ResolutionEngine.skillOrNull(e.getKey());
                if (skill == null || e.getValue() == null) {
                    continue;
                }
                // Base level, never boosted, matching the guide's skill latches.
                int playerLevel = state.getSkillLevel(skill);
                int routeLevel = e.getValue();
                skills.add(new RouteDeficit.SkillDelta(e.getKey(), playerLevel, routeLevel));
            }
        }

        RouteDeficit.SkipAdvice skipAdvice = buildSkipAdvice(budget, idx, gpKnown, playerGp);

        return new RouteDeficit(
                entryId,
                gpKnown,
                playerGp,
                entry.getGpStart(),
                Collections.unmodifiableList(skills),
                skipAdvice);
    }

    /**
     * {@link #build(GuideBudget, String, PlayerState)} plus the quest and diary comparisons.
     * Without a ledger row for the entry, the comparison still carries the quests and diary
     * tasks when any differ.
     *
     * @param activeIdx the active step's position in {@code guide.allSteps()}
     */
    public static RouteDeficit build(GuideBudget budget, String entryId, PlayerState state,
                                     StepwiseGuide guide, int activeIdx) {
        RouteDeficit deficit = build(budget, entryId, state);
        boolean questsKnown = guide != null && state != null && !state.getQuests().isEmpty();
        List<RouteDeficit.QuestBehind> behind =
                questsKnown ? questsBehind(guide, activeIdx, state) : List.of();
        int ahead = questsKnown ? questsAhead(guide, activeIdx, state) : 0;
        List<RouteDeficit.DiaryTaskBehind> diaries = diaryTasksBehind(guide, activeIdx, state);
        if (deficit == null) {
            return entryId == null || (behind.isEmpty() && ahead == 0 && diaries.isEmpty()) ? null
                    : new RouteDeficit(entryId, false, 0L, 0L, List.of(), null,
                            behind, ahead, questsKnown, diaries);
        }
        if (!questsKnown && diaries.isEmpty()) {
            return deficit;
        }
        return new RouteDeficit(deficit.getEntryId(), deficit.isGpKnown(), deficit.getPlayerGp(),
                deficit.getRouteGp(), deficit.getSkills(), deficit.getSkipAdvice(),
                behind, ahead, questsKnown, diaries);
    }

    /**
     * How many quests the player has finished that the guide first finishes at or after
     * {@code activeIdx}. A quest the guide also finishes earlier counts there, not here.
     */
    static int questsAhead(StepwiseGuide guide, int activeIdx, PlayerState state) {
        if (guide == null || state == null) {
            return 0;
        }
        int ahead = 0;
        for (QuestFinish finish : index(guide).finishes) {
            if (finish.stepIdx >= activeIdx
                    && state.getQuests().get(finish.questName) == QuestStatus.COMPLETED) {
                ahead++;
            }
        }
        return ahead;
    }

    /** A quest and the first step that finishes it. */
    @AllArgsConstructor
    private static final class QuestFinish {
        final int stepIdx;
        final String stepId;
        /** As RuneLite's quest list names it. */
        final String questName;
    }

    /** A diary task's condition and the first step that requires it. */
    @AllArgsConstructor
    private static final class DiaryRequirement {
        final int stepIdx;
        /** A {@code varpBitValue} or {@code varbitValue} naming the task. */
        final CompletionCondition condition;
        /** The completion varbit of the tier the task belongs to. */
        final int tierVarbit;
        /** The row reported while the task is undone; built once, as it depends on the guide alone. */
        final RouteDeficit.DiaryTaskBehind row;
    }

    /** What the comparisons read from one guide, and the guide it was built from. */
    @AllArgsConstructor
    private static final class GuideIndex {
        final StepwiseGuide guide;
        /** Each quest the guide finishes, once, at its first finishing step, in guide order. */
        final List<QuestFinish> finishes;
        /**
         * Each diary task a step requires, once per var it reads, at its first such step, in
         * guide order.
         */
        final List<DiaryRequirement> diaries;
    }

    /**
     * Both lists depend on the guide alone, and the comparisons run on every snapshot, so the
     * walk over every step is done once per loaded guide.
     */
    private static volatile GuideIndex guideIndex;

    private static GuideIndex index(StepwiseGuide guide) {
        GuideIndex cached = guideIndex;
        if (cached != null && cached.guide == guide) {
            return cached;
        }
        List<GuideStep> steps = guide.allSteps();
        Set<String> questsSeen = new HashSet<>();
        Set<String> varsSeen = new HashSet<>();
        List<QuestFinish> finishes = new ArrayList<>();
        List<DiaryRequirement> diaries = new ArrayList<>();
        List<CompletionCondition> leaves = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            String stepId = steps.get(i).getId().getValue();
            leaves.clear();
            requiredLeaves(steps.get(i).getCompletion(), leaves);
            for (CompletionCondition leaf : leaves) {
                if (leaf instanceof CompletionCondition.QuestComplete) {
                    String quest = ((CompletionCondition.QuestComplete) leaf).getQuestName();
                    // The quest list is a ConcurrentHashMap, which throws on a null key.
                    if (quest != null) {
                        String name = ResolutionEngine.normalizeQuestName(quest);
                        if (questsSeen.add(name)) {
                            finishes.add(new QuestFinish(i, stepId, name));
                        }
                    }
                    continue;
                }
                String task = diaryTaskOf(leaf);
                Integer tierVarbit = tierVarbitOf(task);
                if (tierVarbit != null && varsSeen.add(varKey(leaf))) {
                    diaries.add(new DiaryRequirement(i, leaf, tierVarbit,
                            new RouteDeficit.DiaryTaskBehind(task, stepId)));
                }
            }
        }
        GuideIndex built = new GuideIndex(guide, Collections.unmodifiableList(finishes),
                Collections.unmodifiableList(diaries));
        guideIndex = built;
        return built;
    }

    /**
     * Quests that a step before {@code activeIdx} finishes and the player has not, in guide
     * order, each named once at the first step that finishes it.
     *
     * <p>Reads the quest list, not step status. A finished quest latches its step wherever the
     * player stands. A quest that a step behind the active step finishes therefore stays
     * unfinished when the player skipped that step, marked it done by hand or passed it
     * through a milestone, or when the step is a background activity not yet done.
     * A quest the list does not name, such as before the login scan, is left out rather than
     * reported as unfinished. A list restored from a save is the whole list as of the last
     * login scan, so a non-empty list is taken as read.
     */
    static List<RouteDeficit.QuestBehind> questsBehind(StepwiseGuide guide, int activeIdx,
                                                       PlayerState state) {
        if (guide == null || state == null || state.getQuests().isEmpty()) {
            return List.of();
        }
        List<RouteDeficit.QuestBehind> behind = new ArrayList<>();
        for (QuestFinish finish : index(guide).finishes) {
            if (finish.stepIdx >= activeIdx) {
                break;
            }
            QuestStatus status = state.getQuests().get(finish.questName);
            if (status == null || status == QuestStatus.COMPLETED) {
                continue;
            }
            behind.add(new RouteDeficit.QuestBehind(finish.questName, finish.stepId,
                    status == QuestStatus.IN_PROGRESS));
        }
        return Collections.unmodifiableList(behind);
    }

    /**
     * Diary tasks that a step before {@code activeIdx} requires and the player has not done,
     * in guide order, each named once at the first step that requires it.
     *
     * <p>Reads the diary vars, not step status, for the same reason {@link #questsBehind}
     * reads the quest list. A var not read yet is left out rather than reported as undone,
     * and so is every task of a tier the game already counts as finished.
     */
    static List<RouteDeficit.DiaryTaskBehind> diaryTasksBehind(StepwiseGuide guide,
                                                               int activeIdx, PlayerState state) {
        if (guide == null || state == null) {
            return List.of();
        }
        List<RouteDeficit.DiaryTaskBehind> behind = new ArrayList<>();
        for (DiaryRequirement requirement : index(guide).diaries) {
            if (requirement.stepIdx >= activeIdx) {
                break;
            }
            // A tier the game already counts as finished owes no tasks.
            Integer tierFlag = state.getVarbits().get(requirement.tierVarbit);
            if (!varRead(requirement.condition, state)
                    || ResolutionEngine.conditionMet(requirement.condition, state)
                    || (tierFlag != null && tierFlag > 0)) {
                continue;
            }
            behind.add(requirement.row);
        }
        return Collections.unmodifiableList(behind);
    }

    /**
     * The leaves a condition cannot latch without: the condition itself, or every required
     * arm of an {@code all} composite, recursively. An {@code any} arm can be met another way.
     */
    private static void requiredLeaves(CompletionCondition condition,
                                       List<CompletionCondition> out) {
        if (!(condition instanceof CompletionCondition.Composite)) {
            out.add(condition);
            return;
        }
        CompletionCondition.Composite composite = (CompletionCondition.Composite) condition;
        if (composite.getMode() != CompositeMode.ALL) {
            return;
        }
        for (CompletionCondition sub : composite.getConditions()) {
            if (!sub.isOptional()) {
                requiredLeaves(sub, out);
            }
        }
    }

    /** The task a diary var condition names, or null for any other condition. */
    private static String diaryTaskOf(CompletionCondition condition) {
        if (condition instanceof CompletionCondition.VarpBitValue) {
            return ((CompletionCondition.VarpBitValue) condition).getTaskDescription();
        }
        if (condition instanceof CompletionCondition.VarbitValue) {
            return ((CompletionCondition.VarbitValue) condition).getTaskDescription();
        }
        return null;
    }

    /**
     * The completion varbit of the diary tier a task label opens with ("Varrock Hard: ..."),
     * or null when the label names no diary tier. The guide labels every diary task this way;
     * other var conditions, such as a barcrawl signature, carry a label that names no tier.
     */
    static Integer tierVarbitOf(String task) {
        int colon = task != null ? task.indexOf(':') : -1;
        int space = colon > 0 ? task.lastIndexOf(' ', colon) : -1;
        if (space <= 0) {
            return null;
        }
        return DiaryVarbits.varbitFor(task.substring(0, space), task.substring(space + 1, colon));
    }

    /**
     * Whether the var behind a diary condition has been read since login. This and
     * {@link #varKey} see only conditions {@link #diaryTaskOf} named, which are always a
     * {@code VarpBitValue} or a {@code VarbitValue}, so the cast after the {@code VarpBitValue} check is safe.
     */
    private static boolean varRead(CompletionCondition condition, PlayerState state) {
        if (condition instanceof CompletionCondition.VarpBitValue) {
            return state.getVarps().containsKey(
                    ((CompletionCondition.VarpBitValue) condition).getVarpId());
        }
        return state.getVarbits().containsKey(
                ((CompletionCondition.VarbitValue) condition).getVarbitId());
    }

    /** One key per var a diary condition reads, so a task repeated later is listed once. */
    private static String varKey(CompletionCondition condition) {
        if (condition instanceof CompletionCondition.VarpBitValue) {
            CompletionCondition.VarpBitValue varp = (CompletionCondition.VarpBitValue) condition;
            return "p" + varp.getVarpId() + ":" + varp.getBit();
        }
        CompletionCondition.VarbitValue varbit = (CompletionCondition.VarbitValue) condition;
        return "b" + varbit.getVarbitId() + ">=" + varbit.getMinValue();
    }

    /** Skip advice for the earn arc opening at {@code idx}, or null when there is no arc. */
    private static RouteDeficit.SkipAdvice buildSkipAdvice(
            GuideBudget budget, int idx, boolean gpKnown, long playerGp) {

        EarnArc arc = earnArcAt(budget, idx);
        if (arc == null || !gpKnown) {
            // Without a bank reading, "you could skip this" might be wrong.
            return null;
        }

        List<String> arcEntryIds = new ArrayList<>();
        Map<String, Long> xp = new HashMap<>();
        for (int i = arc.startIdx; i < arc.endIdxExclusive; i++) {
            arcEntryIds.add(budget.entryAt(i).getEntryId());
            Map<String, Long> gained = budget.entryAt(i).getXpGained();
            if (gained == null) {
                continue;
            }
            for (Map.Entry<String, Long> e : gained.entrySet()) {
                if (e.getValue() != null) {
                    xp.merge(e.getKey(), e.getValue(), Long::sum);
                }
            }
        }
        List<RouteDeficit.XpAward> carried = new ArrayList<>();
        xp.forEach((skill, amount) -> carried.add(new RouteDeficit.XpAward(skill, amount)));
        carried.sort(Comparator.comparingLong(RouteDeficit.XpAward::getXp).reversed()
                .thenComparing(RouteDeficit.XpAward::getSkill));

        return new RouteDeficit.SkipAdvice(
                Collections.unmodifiableList(arcEntryIds),
                arc.horizonGp,
                playerGp >= arc.horizonGp,
                Collections.unmodifiableList(carried));
    }

    /** An earn phase and the balance a player must hold to skip it. */
    @AllArgsConstructor
    static final class EarnArc {
        /** First entry of the arc. */
        final int startIdx;
        /** One past the last entry of the arc. */
        final int endIdxExclusive;
        /** Coins needed on hand to stay solvent until the route next earns its way back. */
        final long horizonGp;
    }

    /**
     * The earn arc opening at {@code startIdx} and its solvency horizon, or null when the entry
     * does not open one.
     *
     * <p>The arc is the run of consecutive earning entries starting here. The route sizes an earn
     * phase as one unit, so the two halves of a buy-low-sell-high arc are not separately
     * skippable.
     *
     * <p>The horizon is the negated trough of a running balance that starts at zero after the
     * arc and stops once the route's next earn makes it solvent again. The arc's
     * closing balance would overstate it: the trough measures what the later spending costs.
     */
    static EarnArc earnArcAt(GuideBudget budget, int startIdx) {
        List<GuideBudget.Entry> entries = budget.getEntries();
        if (startIdx < 0 || startIdx >= entries.size() || entries.get(startIdx).getGpChange() <= 0) {
            return null;
        }

        int arcEnd = startIdx;
        while (arcEnd < entries.size() && entries.get(arcEnd).getGpChange() > 0) {
            arcEnd++;
        }

        long balance = 0;
        long trough = 0;
        boolean dipped = false;
        for (int i = arcEnd; i < entries.size(); i++) {
            balance += entries.get(i).getGpChange();
            if (balance < trough) {
                trough = balance;
            }
            if (balance < 0) {
                dipped = true;
            } else if (dipped) {
                // Solvent again; later spending belongs to a later arc.
                break;
            }
        }

        long horizon = -trough;
        // A zero trough means the route never spends beyond what it earns, so nothing to advise.
        return horizon > 0 ? new EarnArc(startIdx, arcEnd, horizon) : null;
    }

    private static Map<String, Integer> startingLevels() {
        Map<String, Integer> levels = new HashMap<>();
        for (Skill skill : Skill.values()) {
            levels.put(skill.getDisplayName(),
                    skill == Skill.HITPOINTS ? STARTING_HITPOINTS : 1);
        }
        return levels;
    }
}
