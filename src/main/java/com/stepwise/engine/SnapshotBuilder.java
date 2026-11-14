package com.stepwise.engine;

import com.stepwise.guide.AssumptionSpan;
import com.stepwise.guide.CompletionCondition;
import com.stepwise.guide.CompletionCondition.*;
import com.stepwise.guide.CompositeMode;
import com.stepwise.guide.GuideBudget;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.HardwoodCarrier;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.ItemLocation;
import com.stepwise.guide.ItemNeeded;
import com.stepwise.guide.PreStep;
import com.stepwise.guide.RoutePosition;
import com.stepwise.guide.StepAssumption;
import com.stepwise.guide.StepContext;
import com.stepwise.guide.StepId;
import com.stepwise.guide.StepStatus;
import com.stepwise.model.PlayerState;
import com.stepwise.quest.QuestStageData;
import com.stepwise.sailing.BoatVarbits;
import com.stepwise.snapshot.AssumptionNudge;
import com.stepwise.snapshot.CompletionCriterion;
import com.stepwise.snapshot.CriterionProgress;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.DetailedStep;
import com.stepwise.snapshot.GuidePosition;
import com.stepwise.snapshot.ItemNeededStatus;
import com.stepwise.snapshot.NextStep;
import com.stepwise.snapshot.PreStepSummary;
import com.stepwise.snapshot.ResolvedStep;
import com.stepwise.snapshot.RouteDeficit;
import com.stepwise.snapshot.CurrentStepSummary;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.Experience;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    /** Quest stage data for quest progress bars. Set once at startup via {@link #setQuestStageData}. */
    private static QuestStageData questStageData;

    /** The route's per-entry budget ledger, or null when it could not be loaded. */
    private static GuideBudget guideBudget;

    /** Installs quest stage data for quest progress bars. Call once at plugin startup. */
    public static void setQuestStageData(QuestStageData data) {
        questStageData = data;
    }

    /**
     * Installs the budget ledger the route comparison reads.
     * Call once at plugin startup after loading guide_budget.json; null disables the comparison.
     */
    public static void setGuideBudget(GuideBudget budget) {
        guideBudget = budget;
    }

    private static final int COINS_ITEM_ID = 995;
    static final int NEXT_STEPS_COUNT = 3;
    static final int NEXT_DETAILED_COUNT = 2;
    static final int RECENT_COMPLETED_COUNT = 2;
    /** How far past the active step to scan for steps already completed ahead of the route. */
    static final int AHEAD_COMPLETED_WINDOW = 50;

    /**
     * Build snapshot from pre-computed resolved steps, with the player's latest Mark Done and
     * Skip clicks.
     *
     * @param recentManual the latest Mark Done and Skip clicks, newest first; Recent lists
     *                     each one still done or skipped, wherever it sits in the guide
     */
    public static DashboardSnapshot buildSnapshot(
            PlayerState playerState,
            StepwiseGuide guide,
            StepId currentStepId,
            Map<StepId, GuideStep> stepIndex,
            Map<StepId, StepContext> contextMap,
            List<ResolvedStep> resolved,
            List<StepId> recentManual) {

        String playerName = playerState != null ? playerState.getPlayerName() : null;
        SnapshotStepContext ctx = snapshotStepContext(resolved, guide);

        GuidePosition guidePosition = null;
        List<DetailedStep> recentCompleted = Collections.emptyList();
        List<DetailedStep> nextDetailed = Collections.emptyList();
        List<NextStep> nextSteps = Collections.emptyList();
        List<DetailedStep> backgroundSteps = Collections.emptyList();
        RouteDeficit routeDeficit = null;

        if (playerState != null && ctx.activeIdx >= 0) {
            guidePosition = guidePosition(ctx, guide, contextMap);
            StepContext activeCtx = contextMap.get(guide.allSteps().get(ctx.activeIdx).getId());
            // The ledger is keyed per entry, not per step.
            routeDeficit = RouteDeficitBuilder.build(
                    guideBudget,
                    activeCtx != null ? activeCtx.getEntryId() : null,
                    playerState,
                    guide,
                    ctx.activeIdx);
            recentCompleted = findRecentCompleted(ctx, guide, stepIndex, contextMap, playerState,
                    recentManual, RECENT_COMPLETED_COUNT);
            nextDetailed = findNextDetailed(ctx, guide, contextMap, playerState, NEXT_DETAILED_COUNT);
            nextSteps = findNextStepsOffset(ctx, guide, contextMap, NEXT_DETAILED_COUNT, NEXT_STEPS_COUNT);
            backgroundSteps = findBackgroundSteps(ctx, guide, contextMap, playerState);
        }

        // Read from the resolved steps alone, so a missing player state cannot let a step
        // latched ahead of the route stand in for the active one.
        int routePosition = RoutePosition.of(guide.stepPositionIndex(), ctx.activeIdx, ctx.completed);
        CurrentStepSummary stepSummary = null;
        if (currentStepId != null) {
            stepSummary = currentStepSummary(currentStepId, guide, stepIndex, contextMap, playerState,
                    ctx.done, assumptionNudges(guide, routePosition, playerState));
        }

        return DashboardSnapshot.builder()
                .playerName(playerName)
                .currentStep(stepSummary)
                .guidePosition(guidePosition)
                .recentCompleted(recentCompleted)
                .nextDetailed(nextDetailed)
                .nextSteps(nextSteps)
                .backgroundSteps(backgroundSteps)
                .routeDeficit(routeDeficit)
                .kingdomHardwood(kingdomHardwood(guide, ctx.completed, routePosition))
                .build();
    }

    /**
     * The hardwoods the guide accepts on the kingdom's hardwood workers, first choice first:
     * the {@code kingdomHardwood} of the last completed step, in guide order, that carries
     * one and that the route has reached. A carrier latched ahead of the route gives no
     * advice yet. Null before any such step.
     */
    static List<String> kingdomHardwood(StepwiseGuide guide, Set<StepId> completed, int routePosition) {
        List<HardwoodCarrier> carriers = guide.hardwoodCarriers();
        Map<String, Integer> positionIndex = guide.stepPositionIndex();
        for (int i = carriers.size() - 1; i >= 0; i--) {
            HardwoodCarrier carrier = carriers.get(i);
            Integer at = positionIndex.get(carrier.getStepId().getValue());
            if (at != null && at <= routePosition && completed.contains(carrier.getStepId())) {
                return carrier.getHardwoods();
            }
        }
        return null;
    }

    /**
     * The reminder's lead clause, "Out of digsite pendants." → "digsite pendants". The
     * clause ends at a full stop followed by a space or the end, so "2.5k" stays whole.
     */
    private static final Pattern OUT_OF = Pattern.compile("(?i)^Out of (.+?)\\.(?:\\s|$)");
    /** A charge or dose suffix on an item name, "Ring of dueling(8)" or "Digsite pendant (5)". */
    private static final Pattern CHARGES = Pattern.compile("\\s*\\(\\d+\\)$");

    /**
     * Every route assumption in force at {@code routePosition} whose check reads unmet, in
     * guide order.
     *
     * <p>An {@code anywhere} check means "restock", and its nudge stays up from the first
     * bank sighting until the player holds the item again, so the reminder outlasts the bank
     * visit that raised it. Before any sighting the bank's contents are unknown, and an unmet
     * reading means nothing. An {@code inventory} check means "bring it", and its nudge shows
     * only while the bank is open, where the fix is one withdrawal away.
     */
    static List<AssumptionNudge> assumptionNudges(StepwiseGuide guide, int routePosition,
            PlayerState playerState) {
        if (playerState == null || routePosition == RoutePosition.NONE) {
            return Collections.emptyList();
        }
        boolean bankOpen = playerState.isBankOpen();
        boolean bankKnown = bankOpen || playerState.getBankLastSeen() != null;
        if (!bankKnown) {
            return Collections.emptyList();
        }
        // A set, so two spans in force with the same assumption make one chip, not two that
        // open together.
        Set<AssumptionNudge> nudges = null;
        for (AssumptionSpan span : guide.assumptionSpans()) {
            if (!span.inForceAt(routePosition)) {
                continue;
            }
            StepAssumption assumption = span.getAssumption();
            CompletionCondition check = assumption.getCheck();
            ItemLocation location = checkLocation(check);
            if (!bankOpen && location != null && location != ItemLocation.ANYWHERE) {
                continue;
            }
            if (!ResolutionEngine.conditionMet(check, playerState)) {
                if (nudges == null) {
                    nudges = new LinkedHashSet<>();
                }
                nudges.add(new AssumptionNudge(nudgeLabel(assumption), nudgeItemId(check),
                        assumption.getReminder()));
            }
        }
        return nudges == null ? Collections.emptyList() : List.copyOf(nudges);
    }

    /** Where an assumption's check counts the item; null for a check that is not an item check. */
    private static ItemLocation checkLocation(CompletionCondition check) {
        if (check instanceof HasItem) {
            return ((HasItem) check).getLocation();
        }
        if (check instanceof HasItemTotal) {
            return ((HasItemTotal) check).getLocation();
        }
        return null;
    }

    /** The item a nudge's icon shows: the check's item, or the first of a set. 0 for none. */
    private static int nudgeItemId(CompletionCondition check) {
        if (check instanceof HasItem) {
            return ((HasItem) check).getItemId();
        }
        if (check instanceof HasItemTotal && !((HasItemTotal) check).getItems().isEmpty()) {
            return ((HasItemTotal) check).getItems().get(0).getItemId();
        }
        return 0;
    }

    /**
     * A nudge's short name: what the reminder says ran out ("Out of soul runes." → "Soul
     * runes"), else the checked item's name without its charges, else "Restock". A chip shows
     * it, so it never falls back to the whole reminder.
     */
    static String nudgeLabel(StepAssumption assumption) {
        String reminder = assumption.getReminder();
        Matcher outOf = OUT_OF.matcher(reminder != null ? reminder.strip() : "");
        if (outOf.find()) {
            String lead = outOf.group(1).strip();
            return lead.isEmpty() ? lead : Character.toUpperCase(lead.charAt(0)) + lead.substring(1);
        }
        CompletionCondition check = assumption.getCheck();
        String name = null;
        if (check instanceof HasItem) {
            name = ((HasItem) check).getItemName();
        } else if (check instanceof HasItemTotal) {
            name = ((HasItemTotal) check).displayName();
        }
        if (name != null && !name.isBlank()) {
            return CHARGES.matcher(name.strip()).replaceFirst("");
        }
        return "Restock";
    }

    // --- Guide position ---

    static GuidePosition guidePosition(SnapshotStepContext ctx, StepwiseGuide guide,
                                       Map<StepId, StepContext> contextMap) {
        GuideStep activeStep = guide.allSteps().get(ctx.activeIdx);
        StepContext activeCtx = contextMap.get(activeStep.getId());
        return new GuidePosition(
                activeStep.getId().getValue(),
                activeStep.getInstruction(),
                activeCtx != null ? activeCtx.getChapterTitle() : null,
                activeCtx != null ? activeCtx.getSectionTitle() : null,
                ctx.activeIdx,
                guide.allSteps().size());
    }

    // --- Recent completed ---

    /**
     * The rows the Recent section offers Undo on, each step once. The positional rows are the last
     * {@code count} done steps behind the active step and up to {@code count} completions
     * ahead of it, in guide order. Above them, newest first, sit the player's latest Mark Done
     * and Skip clicks that are still done and are not already a positional row. A click on
     * the active step therefore keeps its place in guide order, and only a click made away
     * from the current step (on an In Progress card, or before the route moved on) is lifted
     * above it.
     *
     * <p>The route panel's arc skip records no click: it moves the active step past the arc,
     * whose tail then fills the positional rows behind it. Recording each of its steps would
     * push the player's own clicks out of the bounded list for one action.
     */
    static List<DetailedStep> findRecentCompleted(
            SnapshotStepContext ctx, StepwiseGuide guide,
            Map<StepId, GuideStep> stepIndex,
            Map<StepId, StepContext> contextMap,
            PlayerState playerState, List<StepId> recentManual, int count) {

        List<GuideStep> steps = guide.allSteps();
        List<GuideStep> positional = new ArrayList<>();
        for (int i = ctx.activeIdx - 1; i >= 0 && positional.size() < count; i--) {
            GuideStep s = steps.get(i);
            if (ctx.done.contains(s.getId())) {
                positional.add(s);
            }
        }
        Collections.reverse(positional);

        // Completions ahead of the active step (serves-edge catch-up, monotonic facts) show
        // nowhere else, since the upcoming list skips done steps. They surface here, beside
        // Undo. Skips ahead are left out: a skip made from a step card is among the clicks
        // listed above the positional rows. The scan is bounded so the common zero-ahead case
        // does not walk the guide tail on every snapshot. `count` caps each direction.
        int ahead = 0;
        int windowEnd = Math.min(steps.size(), ctx.activeIdx + 1 + AHEAD_COMPLETED_WINDOW);
        for (int i = ctx.activeIdx + 1; i < windowEnd && ahead < count; i++) {
            GuideStep s = steps.get(i);
            if (ctx.completed.contains(s.getId())) {
                positional.add(s);
                ahead++;
            }
        }

        Set<StepId> listed = new HashSet<>();
        for (GuideStep s : positional) {
            listed.add(s.getId());
        }
        List<DetailedStep> result = new ArrayList<>();
        if (recentManual != null) {
            for (StepId id : recentManual) {
                GuideStep s = stepIndex.get(id);
                if (s != null && ctx.done.contains(id) && listed.add(id)) {
                    result.add(historyRow(s, ctx, contextMap, playerState));
                }
            }
        }
        for (GuideStep s : positional) {
            result.add(historyRow(s, ctx, contextMap, playerState));
        }
        return result;
    }

    /** A Recent row, marked skipped when the step is done without being completed. */
    private static DetailedStep historyRow(
            GuideStep s, SnapshotStepContext ctx, Map<StepId, StepContext> contextMap,
            PlayerState playerState) {
        return detailedStepFrom(s, ctx, contextMap, playerState, !ctx.completed.contains(s.getId()));
    }

    // --- Next detailed ---

    static List<DetailedStep> findNextDetailed(
            SnapshotStepContext ctx, StepwiseGuide guide,
            Map<StepId, StepContext> contextMap,
            PlayerState playerState, int count) {

        List<GuideStep> steps = guide.allSteps();
        List<DetailedStep> result = new ArrayList<>();

        for (int i = ctx.activeIdx + 1; i < steps.size() && result.size() < count; i++) {
            GuideStep s = steps.get(i);
            if (!ctx.done.contains(s.getId()) && !ctx.background.contains(s.getId())) {
                result.add(detailedStepFrom(s, ctx, contextMap, playerState, false));
            }
        }
        return result;
    }

    // --- Background steps ---

    /**
     * Collect every step currently resolved to {@link StepStatus#BACKGROUND}, grouped by
     * occasion ({@link GuideStep#getWhen()}).
     *
     * <p>Scans the whole guide, not a window: a background activity can stay in flight for
     * chapters. Only steps the guide marks as background qualify, so the set is small.
     *
     * <p>Each occasion forms one group, placed where its first step falls in guide order, and
     * keeps guide order inside it. The panel prints one heading per run of equal occasions, so
     * guide order alone would split "Ongoing" or "Next farm run" into several headings whenever
     * another occasion falls between their steps. Steps with no occasion group together too.
     *
     * <p>A step with an unsatisfied {@link GuideStep#getActivation() activation} gate is
     * withheld, because the player cannot start it yet. The gate applies here, at the display
     * boundary, and not in {@link ResolutionEngine}: a step failing the background test there
     * would fall through to ACTIVE/PENDING and seize the active-step pointer or the upcoming
     * lists.
     */
    private static List<DetailedStep> findBackgroundSteps(
            SnapshotStepContext ctx, StepwiseGuide guide,
            Map<StepId, StepContext> contextMap, PlayerState playerState) {

        if (ctx.background.isEmpty()) {
            return Collections.emptyList();
        }

        // Keyed by occasion in first-appearance order; the empty string stands for "no occasion".
        Map<String, List<DetailedStep>> byOccasion = new LinkedHashMap<>();
        for (GuideStep s : guide.allSteps()) {
            if (!ctx.background.contains(s.getId())) {
                continue;
            }
            if (s.getActivation() != null
                    && !ActivationGate.isOpen(s.getActivation(), playerState)) {
                continue;
            }
            String occasion = s.getWhen() != null ? s.getWhen() : "";
            byOccasion.computeIfAbsent(occasion, k -> new ArrayList<>())
                    .add(detailedStepFrom(s, ctx, contextMap, playerState, false));
        }
        List<DetailedStep> result = new ArrayList<>();
        byOccasion.values().forEach(result::addAll);
        return result;
    }

    // --- Next steps (one-liners) ---

    static List<NextStep> findNextStepsOffset(
            SnapshotStepContext ctx, StepwiseGuide guide,
            Map<StepId, StepContext> contextMap,
            int offset, int count) {

        List<GuideStep> steps = guide.allSteps();
        List<NextStep> result = new ArrayList<>();
        int skipped = 0;

        for (int i = ctx.activeIdx + 1; i < steps.size() && result.size() < count; i++) {
            GuideStep s = steps.get(i);
            if (!ctx.done.contains(s.getId()) && !ctx.background.contains(s.getId())) {
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                StepContext sCtx = contextMap.get(s.getId());
                result.add(new NextStep(
                        s.getId().getValue(),
                        s.getInstruction(),
                        sCtx != null ? sCtx.getChapterTitle() : null,
                        sCtx != null ? sCtx.getSectionTitle() : null,
                        buildIconHints(s.getCompletion())));
            }
        }
        return result;
    }

    // --- DetailedStep factory ---

    /** A step as a Recent, Upcoming or Background row; done and skipped steps waive arms. */
    private static DetailedStep detailedStepFrom(
            GuideStep s, SnapshotStepContext snapshotCtx, Map<StepId, StepContext> contextMap,
            PlayerState playerState, boolean skipped) {
        StepContext ctx = contextMap.get(s.getId());
        return new DetailedStep(
                s.getId().getValue(),
                s.getInstruction(),
                s.getGuidance(),
                ctx != null ? ctx.getChapterTitle() : null,
                ctx != null ? ctx.getSectionTitle() : null,
                s.getWhen(),
                buildCriteria(s.getCompletion(), playerState, snapshotCtx.done),
                buildPreStepSummaries(s, playerState),
                onlyAClickCompletes(s.getCompletion()),
                skipped);
    }

    // --- Current step summary ---

    /**
     * The current step as the step card and overlay draw it.
     *
     * @param assumptionNudges the nudges {@link #assumptionNudges} built for this snapshot
     */
    static CurrentStepSummary currentStepSummary(
            StepId stepId, StepwiseGuide guide,
            Map<StepId, GuideStep> stepIndex,
            Map<StepId, StepContext> contextMap,
            PlayerState playerState,
            Set<StepId> completedSteps,
            List<AssumptionNudge> assumptionNudges) {

        List<CompletionCriterion> criteria = Collections.emptyList();
        String guidance = null;
        String fromNowOn = null;
        List<PreStepSummary> preSteps = Collections.emptyList();
        GuideStep step = stepIndex.get(stepId);
        if (step != null) {
            criteria = buildCriteria(step.getCompletion(), playerState, completedSteps);
            guidance = step.getGuidance();
            fromNowOn = step.getFromNowOn() != null && !step.getFromNowOn().isBlank()
                    ? step.getFromNowOn().strip() : null;
            preSteps = buildPreStepSummaries(step, playerState);
        }
        // The items-needed list belongs to the entry, so it comes from the context.
        StepContext ctx = contextMap.get(stepId);
        List<ItemNeeded> itemsNeeded = ctx != null ? ctx.getItemsNeeded() : null;
        boolean alternatives = step != null && criteria.size() > 1
                && step.getCompletion() instanceof Composite
                && ((Composite) step.getCompletion()).getMode() == CompositeMode.ANY;
        return new CurrentStepSummary(
                stepId.getValue(),
                step != null ? step.getInstruction() : null,
                guidance,
                criteria,
                buildItemsNeeded(itemsNeeded, playerState),
                preSteps,
                alternatives,
                step == null || onlyAClickCompletes(step.getCompletion()),
                fromNowOn,
                assumptionNudges);
    }

    /**
     * Whether nothing the game reports at this step completes {@code condition}; Mark Done is
     * the expected action. Mirrors what {@link ResolutionEngine#conditionMet} can never return
     * as met: a manual condition, a combat achievement with no varbit to read, an all-of with
     * such an arm that is not optional, and an any-of whose every arm is one.
     */
    static boolean onlyAClickCompletes(CompletionCondition condition) {
        if (condition instanceof Manual) {
            return true;
        }
        if (condition instanceof CombatAchievement) {
            return ((CombatAchievement) condition).getVarbitId() == null;
        }
        if (condition instanceof Composite) {
            Composite composite = (Composite) condition;
            boolean all = composite.getMode() == CompositeMode.ALL;
            for (CompletionCondition arm : composite.getConditions()) {
                boolean blocked = onlyAClickCompletes(arm);
                if (all && blocked && !arm.isOptional()) {
                    return true;
                }
                if (!all && !blocked) {
                    return false;
                }
            }
            return !all && !composite.getConditions().isEmpty();
        }
        return false;
    }

    // --- Criteria building ---

    /**
     * Build criteria with waiver support. Conditions in a composite whose served
     * step is already completed are marked as waived. Catch-up arms get no row.
     */
    public static List<CompletionCriterion> buildCriteria(
            CompletionCondition condition, PlayerState playerState,
            Set<StepId> completedSteps) {
        if (condition instanceof Manual) {
            return Collections.emptyList();
        }
        if (condition instanceof Composite) {
            Composite c = (Composite) condition;
            Set<Integer> waivedIndices = ResolutionEngine.buildWaivedIndices(c, completedSteps);
            // A taskDescription makes the arms one task to the player ("drink the
            // balm or chart the ocean"), so the card shows one row for the whole composite.
            // That row is waived only while the composite is unmet: a composite met by one arm
            // must not render waived because another arm serves a completed step. The cheap
            // set lookup runs first so the no-serves case skips the second evaluation.
            if (c.hasTaskDescription()) {
                boolean waived = !waivedIndices.isEmpty()
                        && !(playerState != null && ResolutionEngine.conditionMet(c, playerState));
                return Collections.singletonList(buildSingleCriterion(c, playerState, waived));
            }

            List<CompletionCriterion> result = new ArrayList<>();
            for (int i = 0; i < c.getConditions().size(); i++) {
                if (c.getConditions().get(i).isCatchUp()) {
                    continue;
                }
                result.add(buildSingleCriterion(c.getConditions().get(i), playerState,
                        waivedIndices.contains(i)));
            }
            return result;
        }
        return Collections.singletonList(buildSingleCriterion(condition, playerState, false));
    }

    /** The progress bar builders; each returns null for a condition kind it does not draw. */
    private static final List<BiFunction<CompletionCondition, PlayerState, CriterionProgress>>
            PROGRESS_BUILDERS = List.of(
                    SnapshotBuilder::buildItemProgress,
                    SnapshotBuilder::buildGpProgress,
                    SnapshotBuilder::buildSkillLevelProgress,
                    SnapshotBuilder::buildSkillXpProgress,
                    SnapshotBuilder::buildXpGainedProgress,
                    SnapshotBuilder::buildQuestProgress,
                    SnapshotBuilder::buildVarbitProgress);

    static CompletionCriterion buildSingleCriterion(
            CompletionCondition condition, PlayerState playerState, boolean waived) {
        boolean met = playerState != null && ResolutionEngine.conditionMet(condition, playerState);

        CriterionProgress progress = null;
        if (!waived && playerState != null) {
            for (BiFunction<CompletionCondition, PlayerState, CriterionProgress> builder
                    : PROGRESS_BUILDERS) {
                progress = builder.apply(condition, playerState);
                if (progress != null) {
                    break;
                }
            }
        }

        String[] iconHint = iconHintFor(condition);

        return CompletionCriterion.builder()
                .description(describeCriterion(condition))
                .met(met || waived)
                .waived(waived)
                .inBankCount(met || waived ? 0 : bankWithdrawableCount(condition, playerState))
                .iconType(iconHint[0])
                .iconKey(iconHint[1])
                .progress(progress)
                .build();
    }

    /**
     * Banked quantity of the item behind an unmet inventory-scoped {@link HasItem}
     * condition when a bank withdrawal alone would fully satisfy it, so the panel and
     * overlay can send the player to the bank.
     *
     * <p>Returns 0 when the bank has never been seen, and when the bank only partly covers
     * the shortfall. The bank nudge in {@link #buildItemProgress} covers a partial case.
     */
    static int bankWithdrawableCount(CompletionCondition condition, PlayerState playerState) {
        if (!(condition instanceof HasItem) || playerState == null) {
            return 0;
        }
        HasItem c = (HasItem) condition;
        if (c.getLocation() != ItemLocation.INVENTORY || playerState.getBankLastSeen() == null) {
            return 0;
        }

        // Equipment counts toward INVENTORY scope, matching the completion check.
        int heldCount = 0;
        int bankCount = 0;
        for (ItemRef ref : countedRefs(c)) {
            ItemSources counts = itemCounts(ref.getItemId(), ref.getItemName(), playerState);
            heldCount += counts.getInventory() + counts.getEquipment();
            bankCount += counts.getBank();
        }

        if (bankCount > 0 && heldCount < c.getQuantity() && heldCount + bankCount >= c.getQuantity()) {
            return bankCount;
        }
        return 0;
    }

    /**
     * The items a {@link HasItem} counts, from the id set its completion check counts, so a
     * charged item made at a different charge (a Digsite pendant (5) for a condition on the
     * (1)) fills the bar its tick reads as met.
     */
    private static List<ItemRef> countedRefs(HasItem c) {
        Set<Integer> ids = ResolutionEngine.countedIds(c);
        if (ids == null) {
            return List.of(new ItemRef(c.getItemName(), c.getItemId()));
        }
        // No name: an id match never falls back to the name.
        List<ItemRef> refs = new ArrayList<>(ids.size());
        for (int id : ids) {
            refs.add(new ItemRef(null, id));
        }
        return refs;
    }

    /**
     * Where the player holds an item.
     *
     * <p>Side containers come from {@link ResolutionEngine#sideContainerCounts}, the same table
     * the completion check reads, so a bar never disagrees with its own tick. A container that
     * travels (rune pouch, seed box, plank sack) counts as carried; storage the player must
     * walk to, and the tackle box, count as stored.
     */
    private static ItemSources itemCounts(int itemId, String itemName, PlayerState playerState) {
        ResolutionEngine.SideContainerCounts side =
                ResolutionEngine.sideContainerCounts(playerState, itemId, itemName);
        return new ItemSources(
                ResolutionEngine.countItemIn(playerState.getInventory(), itemId, itemName)
                        + side.getCarried(),
                ResolutionEngine.countMatching(playerState.getEquipment().values(), itemId, itemName),
                ResolutionEngine.countItemIn(playerState.getBank(), itemId, itemName)
                        + side.getBanked(),
                side.getStored());
    }

    @Value
    private static class ItemSources {
        int inventory;
        int equipment;
        int bank;
        /**
         * Owned but not to hand: the seed vault, the tool leprechaun, a boat's cargo hold, or
         * the tackle box, whose items must come out first. Counts toward ownership and so toward an
         * ANYWHERE bar, but is not in reach of an inventory- or bank-scoped one.
         */
        int stored;
    }

    // --- Items needed ownership ---

    /**
     * Resolves the entry's items-needed list against the current player state, so
     * {@code ItemsNeededView} only renders verdicts computed here.
     *
     * @return one status per row, in list order, or null when the entry lists no items
     */
    static List<ItemNeededStatus> buildItemsNeeded(List<ItemNeeded> itemsNeeded, PlayerState playerState) {
        if (itemsNeeded == null || itemsNeeded.isEmpty()) {
            return null;
        }
        List<ItemNeededStatus> result = new ArrayList<>(itemsNeeded.size());
        for (ItemNeeded row : itemsNeeded) {
            result.add(itemNeededStatus(row, playerState));
        }
        return result;
    }

    private static ItemNeededStatus itemNeededStatus(ItemNeeded row, PlayerState playerState) {
        Set<Integer> ids = ResolutionEngine.acceptedIdsFor(row.getItemId(), row.getItemClass());
        int resolvedItemId = row.getItemId() != null ? row.getItemId() : 0;
        int resolvedQuantity = row.getQuantity() != null ? row.getQuantity() : 0;

        if (ids.isEmpty()) {
            // A text-only row that resolved to no item. It renders as a muted line.
            return new ItemNeededStatus(row.getText(), 0, resolvedQuantity,
                    ItemNeededStatus.Ownership.UNKNOWN);
        }

        int needed = Math.max(1, resolvedQuantity);
        // Carried is inventory, equipment, and a travelling side container; owned adds the
        // bank and anything the player must travel to fetch. An item class, or one item's
        // upgrade and charge equivalents, sums every interchangeable id.
        int carried = 0;
        int owned = 0;
        for (int id : ids) {
            carried += ResolutionEngine.countCarried(playerState, id, null);
            owned += ResolutionEngine.countOwned(playerState, id, null);
        }

        ItemNeededStatus.Ownership ownership;
        if (carried >= needed) {
            // Inventory is observed fact, so a carried item never waits on an unopened bank.
            ownership = ItemNeededStatus.Ownership.CARRIED;
        } else if (playerState == null || playerState.getBankLastSeen() == null) {
            // The bank list counts only once bankLastSeen is set, as in bankWithdrawableCount.
            ownership = ItemNeededStatus.Ownership.UNKNOWN;
        } else if (owned >= needed) {
            ownership = ItemNeededStatus.Ownership.BANKED;
        } else {
            ownership = ItemNeededStatus.Ownership.MISSING;
        }
        return new ItemNeededStatus(row.getText(), resolvedItemId, resolvedQuantity, ownership);
    }

    // --- Pre-step evaluation ---

    /** Evaluates each pre-step's completion condition against the current player state. */
    static List<PreStepSummary> buildPreStepSummaries(GuideStep step, PlayerState playerState) {
        List<PreStep> preSteps = step.getPreSteps();
        if (preSteps == null || preSteps.isEmpty()) {
            return Collections.emptyList();
        }
        List<PreStepSummary> result = new ArrayList<>(preSteps.size());
        for (PreStep ps : preSteps) {
            CompletionCriterion criterion = buildSingleCriterion(ps.getCompletion(), playerState, false);
            result.add(new PreStepSummary(ps.getInstruction(), criterion));
        }
        return result;
    }

    // --- Item progress ---

    /**
     * Builds a progress bar for a {@link HasItem} condition, or for a {@link HasItemTotal}
     * from the combined count of every listed item.
     */
    static CriterionProgress buildItemProgress(
            CompletionCondition condition, PlayerState playerState) {
        boolean single = condition instanceof HasItem;
        List<ItemRef> items;
        int quantity;
        ItemLocation location;
        if (single) {
            HasItem c = (HasItem) condition;
            items = countedRefs(c);
            quantity = c.getQuantity();
            location = c.getLocation();
        } else if (condition instanceof HasItemTotal) {
            HasItemTotal c = (HasItemTotal) condition;
            items = c.getItems();
            quantity = c.getQuantity();
            location = c.getLocation();
        } else {
            return null;
        }
        if (quantity <= 1) {
            return null;
        }

        int invCount = 0;
        int equipCount = 0;
        int bankCount = 0;
        int storedCount = 0;
        for (ItemRef ref : items) {
            ItemSources counts = itemCounts(ref.getItemId(), ref.getItemName(), playerState);
            invCount += counts.getInventory();
            equipCount += counts.getEquipment();
            bankCount += counts.getBank();
            storedCount += counts.getStored();
        }

        int held = invCount + equipCount;
        int effective;
        if (location == ItemLocation.INVENTORY) {
            effective = held;
        } else if (location == ItemLocation.BANK) {
            effective = bankCount;
        } else {
            // Owned storage counts only here, matching what makes the criterion met.
            effective = held + bankCount + storedCount;
        }

        // Pending: items that would count once deposited (BANK target) or withdrawn
        // (INVENTORY target). Only a single-item condition shows them.
        int pending = 0;
        String bankNudge = null;
        if (single && location == ItemLocation.BANK && held > 0 && effective < quantity) {
            pending = Math.min(held, quantity - effective);
        } else if (single && location == ItemLocation.INVENTORY && bankCount > 0 && held < quantity) {
            bankNudge = formatNumber(bankCount) + " in your bank";
            pending = Math.min(bankCount, quantity - held);
        }

        return CriterionProgress.builder()
                .current(Math.min(effective, quantity))
                .required(quantity)
                .pending(pending)
                .bankNudge(bankNudge)
                .build();
    }

    // --- GP progress ---

    static CriterionProgress buildGpProgress(
            CompletionCondition condition, PlayerState playerState) {
        if (!(condition instanceof GpThreshold)) {
            return null;
        }
        GpThreshold c = (GpThreshold) condition;
        int required = (int) Math.min(c.getMinGp(), Integer.MAX_VALUE);
        ItemLocation loc = c.getLocation();

        int invCount = loc == ItemLocation.BANK ? 0
                : ResolutionEngine.countItemIn(playerState.getInventory(), COINS_ITEM_ID, "Coins");
        int bankCount = loc == ItemLocation.INVENTORY ? 0
                : ResolutionEngine.countItemIn(playerState.getBank(), COINS_ITEM_ID, "Coins");

        return CriterionProgress.builder()
                .current(Math.min(invCount + bankCount, required))
                .required(required)
                .build();
    }

    // --- Skill progress ---

    /** Returns the level corresponding to a total XP value (1–99). */
    static int levelForXp(long xp) {
        int clamped = (int) Math.max(0, Math.min(xp, Experience.MAX_SKILL_XP));
        return Math.min(Experience.getLevelForXp(clamped), Experience.MAX_REAL_LEVEL);
    }

    /** Returns the total XP required to reach a level, clamped to 1–99. */
    public static int xpForLevel(int level) {
        return level < 1 ? 0 : Experience.getXpForLevel(Math.min(level, Experience.MAX_REAL_LEVEL));
    }

    static CriterionProgress buildSkillLevelProgress(
            CompletionCondition condition, PlayerState playerState) {
        if (!(condition instanceof SkillLevel) || playerState == null) {
            return null;
        }
        SkillLevel c = (SkillLevel) condition;
        return levelBar(playerState, c.getSkill(), xpForLevel(c.getLevel()), c.getLevel());
    }

    static CriterionProgress buildSkillXpProgress(
            CompletionCondition condition, PlayerState playerState) {
        if (!(condition instanceof SkillXp) || playerState == null) {
            return null;
        }
        SkillXp c = (SkillXp) condition;
        return levelBar(playerState, c.getSkill(), c.getMinXp(), levelForXp(c.getMinXp()));
    }

    /**
     * An XP bar toward {@code targetXp}, marked at each level between the player's and
     * {@code targetLevel}. Null for an unknown skill name.
     */
    private static CriterionProgress levelBar(PlayerState playerState, String skillName,
                                              long targetXp, int targetLevel) {
        if (ResolutionEngine.skillOrNull(skillName) == null) {
            return null;
        }
        long currentXp = ResolutionEngine.skillXp(playerState, skillName);
        int currentLevel = levelForXp(currentXp);

        // XP thresholds for the levels strictly between current and target, drawn as markers.
        int[] milestones = null;
        if (targetLevel > currentLevel + 1) {
            milestones = new int[targetLevel - currentLevel - 1];
            for (int i = 0; i < milestones.length; i++) {
                milestones[i] = xpForLevel(currentLevel + i + 1);
            }
        }

        return CriterionProgress.builder()
                .current((int) Math.min(currentXp, targetXp))
                .required((int) targetXp)
                .currentLevel(currentLevel)
                .targetLevel(targetLevel)
                .levelMilestoneXp(milestones)
                .build();
    }

    static CriterionProgress buildXpGainedProgress(
            CompletionCondition condition, PlayerState playerState) {
        if (!(condition instanceof XpGained) || playerState == null) {
            return null;
        }
        XpGained c = (XpGained) condition;
        if (ResolutionEngine.skillOrNull(c.getSkill()) == null) {
            return null;
        }
        int required = (int) Math.min(c.getMinXp(), Integer.MAX_VALUE);
        return CriterionProgress.builder()
                .current((int) Math.min(ResolutionEngine.skillXp(playerState, c.getSkill()), required))
                .required(required)
                .build();
    }

    // --- Quest progress ---

    /**
     * Builds a quest progress bar from the player's position in the quest's stage array.
     * {@code questComplete} measures against the total stages, {@code questProgress} against
     * the target stage index.
     */
    static CriterionProgress buildQuestProgress(
            CompletionCondition condition, PlayerState playerState) {
        if (questStageData == null || playerState == null) {
            return null;
        }

        String questName;
        int targetStages;

        if (condition instanceof QuestComplete) {
            questName = ResolutionEngine.normalizeQuestName(((QuestComplete) condition).getQuestName());
            int total = questStageData.getTotalStages(questName);
            if (total <= 1) {
                return null;
            }
            targetStages = total;
        } else if (condition instanceof QuestProgress) {
            QuestProgress c = (QuestProgress) condition;
            questName = ResolutionEngine.normalizeQuestName(c.getQuestName());
            int targetIdx = questStageData.getTargetStageIndex(questName, c.getMinValue());
            if (targetIdx <= 0) {
                // Target stage not found or is the first stage — no meaningful bar
                return null;
            }
            targetStages = targetIdx;
        } else {
            return null;
        }

        int currentIdx = questStageData.getStageIndex(questName,
                playerState.getQuestProgress().getOrDefault(questName, 0));

        return CriterionProgress.builder()
                .current(Math.min(currentIdx, targetStages))
                .required(targetStages)
                .build();
    }

    static CriterionProgress buildVarbitProgress(
            CompletionCondition condition, PlayerState playerState) {
        if (!(condition instanceof VarbitValue) || playerState == null) {
            return null;
        }
        VarbitValue c = (VarbitValue) condition;
        // Only an open-ended threshold above 1 (a percentage or counter) gets a bar. Binary
        // flags and bounded checks (maxValue set) have no fill direction.
        if (c.getMinValue() <= 1 || c.getMaxValue() != null) {
            return null;
        }
        return CriterionProgress.builder()
                .current(Math.min(playerState.getVarbits().getOrDefault(c.getVarbitId(), 0),
                        c.getMinValue()))
                .required(c.getMinValue())
                .build();
    }

    // --- Icon hints ---

    /**
     * Maps a keyed icon type to its generic equivalent for one-liner display. These types
     * draw async sprites, which one-liners skip because they may not load before first render.
     */
    private static final Map<String, String> GENERIC_ICON_TYPE = Map.of(
        "item", "hasItem",
        "skill", "skills"
    );

    /**
     * Builds one-liner icon hints for a completion condition. A composite yields one hint
     * per distinct generic type among its arms.
     */
    static List<NextStep.IconHint> buildIconHints(CompletionCondition condition) {
        if (condition == null) {
            return Collections.emptyList();
        }
        if (condition instanceof Composite) {
            List<NextStep.IconHint> hints = new ArrayList<>();
            Set<String> seenTypes = new HashSet<>();
            for (CompletionCondition sub : ((Composite) condition).getConditions()) {
                if (sub.isCatchUp()) {
                    continue;
                }
                String[] hint = iconHintFor(sub);
                if (hint[0] == null) {
                    continue;
                }
                String dedupeKey = GENERIC_ICON_TYPE.getOrDefault(hint[0], hint[0]);
                if (seenTypes.add(dedupeKey)) {
                    hints.add(new NextStep.IconHint(dedupeKey, null));
                }
            }
            return hints;
        }
        String[] hint = iconHintFor(condition);
        if (hint[0] == null) {
            return Collections.emptyList();
        }
        String type = GENERIC_ICON_TYPE.getOrDefault(hint[0], hint[0]);
        String key = GENERIC_ICON_TYPE.containsKey(hint[0]) ? null : hint[1];
        return Collections.singletonList(new NextStep.IconHint(type, key));
    }

    static String[] iconHintFor(CompletionCondition condition) {
        if (condition instanceof SkillLevel) {
            return new String[]{"skill", ((SkillLevel) condition).getSkill()};
        }
        if (condition instanceof SkillXp) {
            return new String[]{"skill", ((SkillXp) condition).getSkill()};
        }
        if (condition instanceof XpGained) {
            return new String[]{"skill", ((XpGained) condition).getSkill()};
        }
        if (condition instanceof HasItem) {
            HasItem c = (HasItem) condition;
            if (c.getItemId() == COINS_ITEM_ID) {
                return new String[]{"gp", String.valueOf(c.getQuantity())};
            }
            String key = c.getItemId() != 0 ? String.valueOf(c.getItemId()) : null;
            return new String[]{"item", key};
        }
        if (condition instanceof HasItemTotal) {
            // The first item's sprite stands for the set.
            HasItemTotal c = (HasItemTotal) condition;
            String key = null;
            if (!c.getItems().isEmpty() && c.getItems().get(0).getItemId() != 0) {
                key = String.valueOf(c.getItems().get(0).getItemId());
            }
            return new String[]{"item", key};
        }
        if (condition instanceof CollectionLog) {
            return new String[]{"item", null};
        }
        if (condition instanceof QuestComplete || condition instanceof QuestStarted
                || condition instanceof QuestProgress) {
            return new String[]{"quest", null};
        }
        if (condition instanceof VarbitValue || condition instanceof VarpBitValue
                || condition instanceof DiaryComplete || condition instanceof DiaryReward) {
            return new String[]{"diary", null};
        }
        if (condition instanceof GpThreshold) {
            return new String[]{"gp", String.valueOf(((GpThreshold) condition).getMinGp())};
        }
        if (condition instanceof KillCount) {
            return new String[]{"kill", null};
        }
        if (condition instanceof CombatAchievement) {
            return new String[]{"combat_achievement", null};
        }
        if (condition instanceof LocationReached) {
            return new String[]{"location", null};
        }
        if (condition instanceof ObjectPresent) {
            // Scene object ids cannot be drawn. Some builds carry the item that the Construction
            // skill guide shows for them. The rest draw the Construction skill icon, because
            // every objectPresent is a Construction build.
            Integer iconItemId = ((ObjectPresent) condition).getIconItemId();
            if (iconItemId != null) {
                return new String[]{"item", String.valueOf(iconItemId)};
            }
            return new String[]{"skill", "Construction"};
        }
        if (condition instanceof SlayerTaskComplete || condition instanceof NoSlayerTask) {
            return new String[]{"skill", "Slayer"};
        }
        if (condition instanceof BoatOwned || condition instanceof BoatComponent
                || condition instanceof BoatFacility) {
            return new String[]{"skill", "Sailing"};
        }
        if (condition instanceof Composite) {
            // A composite borrows the icon of its first drawable arm.
            String[] keyless = null;
            for (CompletionCondition sub : ((Composite) condition).getConditions()) {
                if (sub.isCatchUp()) {
                    continue;
                }
                String[] hint = iconHintFor(sub);
                if (hint[0] == null) {
                    continue;
                }
                // "item" and "skill" draw nothing without a key. Every other type has a
                // static sprite, so only those two are passed over for a later arm.
                boolean drawable = hint[1] != null
                        || (!"item".equals(hint[0]) && !"skill".equals(hint[0]));
                if (drawable) {
                    return hint;
                }
                if (keyless == null) {
                    keyless = hint;
                }
            }
            if (keyless != null) {
                return keyless;
            }
        }
        return new String[]{null, null};
    }

    // --- Criterion descriptions ---

    static String describeCriterion(CompletionCondition condition) {
        if (condition instanceof SkillLevel) {
            SkillLevel c = (SkillLevel) condition;
            return c.getSkill() + " level " + c.getLevel();
        }
        if (condition instanceof CompletionCondition.CombatLevel) {
            return "Combat level "
                    + ((CompletionCondition.CombatLevel) condition).getLevel();
        }
        if (condition instanceof SkillXp) {
            SkillXp c = (SkillXp) condition;
            return c.getSkill() + " " + formatNumber(c.getMinXp()) + " XP";
        }
        if (condition instanceof QuestComplete) {
            return "Complete " + ((QuestComplete) condition).getQuestName();
        }
        if (condition instanceof HasItem) {
            HasItem c = (HasItem) condition;
            return describeHave(c.getQuantity(), c.getItemName(), c.getLocation());
        }
        if (condition instanceof HasItemTotal) {
            HasItemTotal c = (HasItemTotal) condition;
            // Name the set by its shared display name, or list its items when they share none.
            String name = c.displayName();
            if (name == null) {
                StringJoiner names = new StringJoiner(" or ");
                for (ItemRef ref : c.getItems()) {
                    if (ref.getItemName() != null && !ref.getItemName().isBlank()) {
                        names.add(ref.getItemName().trim());
                    }
                }
                if (names.length() == 0) {
                    // Nothing to name, so the set is described by its count alone.
                    return "Have " + (c.getQuantity() > 1 ? formatNumber(c.getQuantity()) + " of " : "")
                            + "the required items" + locationSuffix(c.getLocation());
                }
                name = names.toString();
            }
            return describeHave(c.getQuantity(), name, c.getLocation());
        }
        if (condition instanceof GpThreshold) {
            GpThreshold gc = (GpThreshold) condition;
            return "Have " + formatNumber(gc.getMinGp()) + " gp" + locationSuffix(gc.getLocation());
        }
        if (condition instanceof DiaryComplete) {
            DiaryComplete c = (DiaryComplete) condition;
            return "Complete the " + c.getArea() + " " + c.getTier() + " diary";
        }
        if (condition instanceof DiaryReward) {
            DiaryReward c = (DiaryReward) condition;
            return "Claim the " + c.getArea() + " " + c.getTier() + " diary reward";
        }
        if (condition instanceof CombatAchievement) {
            return "Earn the " + ((CombatAchievement) condition).getName() + " Combat Achievement";
        }
        if (condition instanceof XpGained) {
            // Worded as skillXp is: the check is the skill's whole total, not a gain from a
            // baseline, so "Gain 500 XP" would ask for more than it measures.
            XpGained c = (XpGained) condition;
            return c.getSkill() + " " + formatNumber(c.getMinXp()) + " XP";
        }
        if (condition instanceof KillCount) {
            KillCount c = (KillCount) condition;
            return c.getCount() == 1
                    ? "Kill " + c.getTarget()
                    : "Kill " + formatNumber(c.getCount()) + "x " + c.getTarget();
        }
        if (condition instanceof CollectionLog) {
            return "Add " + ((CollectionLog) condition).getItemName() + " to your collection log";
        }
        if (condition instanceof LocationReached) {
            LocationReached loc = (LocationReached) condition;
            // Never show a raw region id to the player.
            return loc.getRegionName() != null
                    ? "Reach " + loc.getRegionName()
                    : "Reach the destination";
        }
        if (condition instanceof QuestStarted) {
            return "Start " + ((QuestStarted) condition).getQuestName();
        }
        if (condition instanceof QuestProgress) {
            QuestProgress c = (QuestProgress) condition;
            String questName = ResolutionEngine.normalizeQuestName(c.getQuestName());
            if (questStageData != null) {
                int targetIdx = questStageData.getTargetStageIndex(questName, c.getMinValue());
                if (targetIdx >= 0) {
                    String desc = questStageData.getStageDescription(questName, targetIdx);
                    if (desc != null) {
                        return c.getQuestName() + " — " + desc;
                    }
                }
            }
            return "Continue " + c.getQuestName();
        }
        if (condition instanceof VarbitValue) {
            VarbitValue c = (VarbitValue) condition;
            return c.getTaskDescription() != null ? c.getTaskDescription() : "Complete the objective";
        }
        if (condition instanceof VarpBitValue) {
            VarpBitValue c = (VarpBitValue) condition;
            return c.getTaskDescription() != null ? c.getTaskDescription() : "Complete the objective";
        }
        if (condition instanceof CompletionCondition.VarpValue) {
            CompletionCondition.VarpValue c = (CompletionCondition.VarpValue) condition;
            return c.getTaskDescription() != null ? c.getTaskDescription() : "Complete the objective";
        }
        if (condition instanceof ObjectPresent) {
            ObjectPresent c = (ObjectPresent) condition;
            // Object ids mean nothing to a player, so only the name is shown.
            return c.getObjectName() != null ? "Build " + c.getObjectName() : "Build the object";
        }
        if (condition instanceof SlayerTaskComplete) {
            SlayerTaskComplete c = (SlayerTaskComplete) condition;
            String tasks = c.getCount() > 1 ? c.getCount() + " Slayer tasks" : "a Slayer task";
            return c.getMaster() != null
                    ? "Finish " + tasks + " from " + c.getMaster()
                    : "Finish " + tasks;
        }
        if (condition instanceof NoSlayerTask) {
            // Phrased as a state, not an instruction: beside "Finish a Slayer task", an
            // instruction would read as telling the player to drop that task.
            return "No Slayer task in hand";
        }
        if (condition instanceof NpcInteraction) {
            NpcInteraction c = (NpcInteraction) condition;
            if (c.getAction() != null) {
                // Menu options hyphenate ("Talk-to"); the instruction reads as plain words.
                return c.getAction().replace('-', ' ') + " " + c.getNpcName();
            }
            return "Talk to " + c.getNpcName();
        }
        if (condition instanceof BoatOwned) {
            BoatOwned c = (BoatOwned) condition;
            return "Own a " + (c.getBoatType() != null ? c.getBoatType().toLowerCase(Locale.ROOT) : "boat");
        }
        if (condition instanceof BoatComponent) {
            return describeBoatComponent((BoatComponent) condition);
        }
        if (condition instanceof BoatFacility) {
            return describeBoatFacility((BoatFacility) condition);
        }
        if (condition instanceof Manual) {
            return "Click Mark Done when finished";
        }
        if (condition instanceof Composite) {
            return describeComposite((Composite) condition);
        }
        return "Complete the objective";
    }

    private static String describeHave(int quantity, String name, ItemLocation location) {
        // A count reads "10x Snail", as the game writes item counts: names keep the game's own
        // casing and stay singular, since no rule pluralises every item name correctly.
        return "Have " + (quantity > 1 ? formatNumber(quantity) + "x " : "") + name + locationSuffix(location);
    }

    private static String locationSuffix(ItemLocation location) {
        if (location == ItemLocation.INVENTORY) {
            return " in your inventory";
        }
        return location == ItemLocation.BANK ? " in your bank" : "";
    }

    /** Metal-part tiers (keel, helm) as the game numbers them: a bare boat is bronze at 0. */
    private static final String[] METAL_TIERS = {
        "bronze", "iron", "steel", "mithril", "adamant", "rune", "dragon",
    };
    /** Wood-part tiers (hull, mast) as the game numbers them: a bare boat is wooden at 0. */
    private static final String[] WOOD_TIERS = {
        "wooden", "oak", "teak", "mahogany", "camphor", "ironwood", "rosewood",
    };
    /** The sails' cloth at each wood tier: "Teak mast and canvas sails". */
    private static final String[] SAIL_CLOTHS = {
        "linen", "linen", "canvas", "canvas", "canvas", "cotton", "cotton",
    };

    /**
     * Player-facing label for a boat-facility criterion, e.g. "Build an innoculation station on
     * your sloop".
     */
    private static String describeBoatFacility(BoatFacility c) {
        String canonical = BoatVarbits.canonicalFacilityName(c.getFacility());
        return describeBuild(canonical != null ? canonical : "facility", c.getBoatType());
    }

    /**
     * Player-facing label for a boat-component criterion, naming the part at its tier the way
     * the shipwright does: "Build an iron helm on your skiff", "Build a teak mast and canvas
     * sails on your skiff". A tier past the game's last one names the bare part.
     */
    private static String describeBoatComponent(BoatComponent c) {
        String comp = c.getComponent() != null ? c.getComponent().toLowerCase(Locale.ROOT) : "";
        int tier = c.getMinTier();
        String part;
        switch (comp) {
            case "steering":
                part = tierName(METAL_TIERS, tier) + "helm";
                break;
            case "keel":
                part = tierName(METAL_TIERS, tier) + "keel";
                break;
            case "hull":
                part = tierName(WOOD_TIERS, tier) + "hull";
                break;
            case "sail":
                part = tierName(WOOD_TIERS, tier) + "mast and " + tierName(SAIL_CLOTHS, tier) + "sails";
                break;
            case "brazier":
                part = "eternal brazier";
                break;
            default:
                part = comp.isEmpty() ? "boat part" : comp;
        }
        return describeBuild(part, c.getBoatType());
    }

    private static String describeBuild(String thing, String boatType) {
        String article = "aeiou".indexOf(thing.charAt(0)) >= 0 ? "an " : "a ";
        String boat = boatType != null ? boatType.toLowerCase(Locale.ROOT) : "boat";
        return "Build " + article + thing + " on your " + boat;
    }

    /** The tier's word followed by a space, or nothing for a tier outside the table. */
    private static String tierName(String[] tiers, int tier) {
        return tier >= 0 && tier < tiers.length ? tiers[tier] + " " : "";
    }

    /**
     * Describes a composite by spelling out its arms, joined by the composite's mode.
     *
     * <p>Arms with the same description collapse to one. A nested composite of several arms
     * is parenthesised so "or" and "and" stay unambiguous. A verb shared by every arm
     * ("Have") is kept only on the first.
     *
     * @param composite the composite to describe; may nest further composites
     * @return a player-facing description naming every distinct arm
     */
    static String describeComposite(Composite composite) {
        if (composite.hasTaskDescription()) {
            return composite.getTaskDescription();
        }
        return joinArms(composite, describeArms(composite));
    }

    /**
     * The distinct descriptions of a composite's arms, in order. A nested composite is
     * bracketed only when it describes as more than one thing. One that collapses to a single
     * description takes no brackets, so it can still share a verb with its siblings.
     */
    private static List<String> describeArms(Composite composite) {
        List<String> parts = new ArrayList<>();
        for (CompletionCondition sub : composite.getConditions()) {
            if (sub.isCatchUp()) {
                continue;
            }
            String description;
            if (sub instanceof Composite) {
                Composite nested = (Composite) sub;
                if (nested.hasTaskDescription()) {
                    description = nested.getTaskDescription();
                } else {
                    List<String> innerParts = describeArms(nested);
                    String inner = joinArms(nested, innerParts);
                    description = innerParts.size() > 1 ? "(" + inner + ")" : inner;
                }
            } else {
                description = describeCriterion(sub);
            }
            if (!parts.contains(description)) {
                parts.add(description);
            }
        }
        return parts;
    }

    /** Joins arm descriptions with the word the composite's own mode calls for. */
    private static String joinArms(Composite composite, List<String> parts) {
        if (parts.isEmpty()) {
            return "Complete the objective";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String verb = sharedLeadingVerb(parts);
        StringBuilder result = new StringBuilder(parts.get(0));
        String joiner = composite.getMode() == CompositeMode.ANY ? " or " : " and ";
        for (int i = 1; i < parts.size(); i++) {
            String part = parts.get(i);
            result.append(joiner)
                    .append(verb != null ? part.substring(verb.length() + 1) : part);
        }
        return result.toString();
    }

    /**
     * Verbs that {@link #describeCriterion} opens a description with, and the only words
     * safe to elide from repeated arms. Other branches lead with a meaningful noun (a quest
     * name, a skill, a task label), and eliding "Forgettable" from "Forgettable Tale: ..."
     * would corrupt the sentence.
     */
    private static final Set<String> ELIDABLE_VERBS = Set.of(
            "Have", "Complete", "Start", "Continue", "Kill", "Add", "Reach",
            "Build", "Claim", "Earn", "Finish", "Talk to", "Own");

    /**
     * The verb every description opens with, or null when they do not share one.
     * A bracketed nested arm leads with "(Have", so it never matches and keeps its verb.
     */
    private static String sharedLeadingVerb(List<String> parts) {
        String first = parts.get(0);
        int space = first.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        // "Talk to" goes as one verb, so two NPCs read "Talk to A or B", not "A or to B".
        String verb = first.startsWith("Talk to ") ? "Talk to" : first.substring(0, space);
        if (!ELIDABLE_VERBS.contains(verb)) {
            return null;
        }
        for (String part : parts) {
            if (!part.startsWith(verb + " ")) {
                return null;
            }
        }
        return verb;
    }

    // --- Number formatting ---

    /** Groups thousands with commas whatever the client's locale. */
    static String formatNumber(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    // --- Step context helper ---

    /** Where the route stands, read once per snapshot from the resolved steps. */
    @AllArgsConstructor
    static final class SnapshotStepContext {
        /** The ACTIVE step's position in {@code guide.allSteps()}, or -1 when none is active. */
        final int activeIdx;
        /** COMPLETED and SKIPPED steps; they waive the composite arms that serve them. */
        final Set<StepId> done;
        /** Genuinely COMPLETED steps only — excludes SKIPPED, unlike {@link #done}. */
        final Set<StepId> completed;
        /**
         * Steps resolved to {@link StepStatus#BACKGROUND}. They render in their own panel
         * section, never in the upcoming lists.
         */
        final Set<StepId> background;
    }

    static SnapshotStepContext snapshotStepContext(List<ResolvedStep> resolved, StepwiseGuide guide) {
        int activeIdx = -1;
        Set<StepId> done = new HashSet<>();
        Set<StepId> completed = new HashSet<>();
        Set<StepId> background = new HashSet<>();
        for (ResolvedStep r : resolved) {
            if (r.getStatus() == StepStatus.ACTIVE && activeIdx < 0) {
                activeIdx = guide.stepPositionIndex().getOrDefault(r.getStepId().getValue(), -1);
            } else if (r.getStatus() == StepStatus.COMPLETED) {
                done.add(r.getStepId());
                completed.add(r.getStepId());
            } else if (r.getStatus() == StepStatus.SKIPPED) {
                done.add(r.getStepId());
            } else if (r.getStatus() == StepStatus.BACKGROUND) {
                background.add(r.getStepId());
            }
        }
        return new SnapshotStepContext(activeIdx, done, completed, background);
    }
}
