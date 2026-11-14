package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Value
@Builder
public class StepwiseGuide {
    @SerializedName("title") String title;
    @SerializedName("version") String version;
    /**
     * The guide's whole length in minutes of play, by the guide's own timings: the sum of every
     * entry's {@link Entry#getEstimatedDurationMinutes()}.
     */
    @SerializedName("totalMinutes") Double totalMinutes;
    @SerializedName("chapters") List<Chapter> chapters;

    /** All steps in guide order. Populated by {@link GuideLoader#freezeGuide(StepwiseGuide)}. */
    @lombok.Getter(lombok.AccessLevel.NONE)
    transient List<GuideStep> allStepsCached;

    /** Step ID &rarr; chapter/section/entry context. Populated by {@link GuideLoader#freezeGuide(StepwiseGuide)}. */
    @lombok.Getter(lombok.AccessLevel.NONE)
    transient Map<StepId, StepContext> stepContextMapCached;

    /**
     * Step ID string &rarr; position in {@link #allSteps()}. Populated by
     * {@link GuideLoader#freezeGuide(StepwiseGuide)} so a snapshot does not rebuild it.
     */
    @lombok.Getter(lombok.AccessLevel.NONE)
    transient Map<String, Integer> stepPositionIndexCached;

    /** Every step's route assumptions, placed by guide position. Populated by {@link GuideLoader#freezeGuide(StepwiseGuide)}. */
    @lombok.Getter(lombok.AccessLevel.NONE)
    transient List<AssumptionSpan> assumptionSpansCached;

    /** Steps carrying {@link GuideStep#getKingdomHardwood()}, in guide order. Populated by {@link GuideLoader#freezeGuide(StepwiseGuide)}. */
    @lombok.Getter(lombok.AccessLevel.NONE)
    transient List<HardwoodCarrier> hardwoodCarriersCached;

    public List<GuideStep> allSteps() {
        return allStepsCached;
    }

    public Map<StepId, StepContext> stepContextMap() {
        return stepContextMapCached;
    }

    public Map<String, Integer> stepPositionIndex() {
        return stepPositionIndexCached;
    }

    /**
     * Minutes of play the completed steps account for, by the guide's own timings. Each step takes an equal
     * share of its entry's minutes, since the guide times entries, not steps. An untimed or
     * step-less entry counts nothing, and so does a completed id the loaded guide lacks.
     */
    public double minutesDone(Set<StepId> completed) {
        double done = 0;
        if (chapters == null) {
            return done;
        }
        for (Chapter chapter : chapters) {
            if (chapter.getSections() == null) continue;
            for (Section section : chapter.getSections()) {
                if (section.getEntries() == null) continue;
                for (Entry entry : section.getEntries()) {
                    List<GuideStep> steps = entry.getSteps();
                    Double minutes = entry.getEstimatedDurationMinutes();
                    if (steps == null || steps.isEmpty() || minutes == null) continue;
                    double share = minutes / steps.size();
                    for (GuideStep step : steps) {
                        if (completed.contains(step.getId())) {
                            done += share;
                        }
                    }
                }
            }
        }
        return done;
    }

    /** Every step's route assumptions, placed by guide position, in guide order. */
    public List<AssumptionSpan> assumptionSpans() {
        return assumptionSpansCached;
    }

    /** Steps that set the Miscellania hardwood advice, in guide order. */
    public List<HardwoodCarrier> hardwoodCarriers() {
        return hardwoodCarriersCached;
    }

    /**
     * Every condition the guide asks about, step and pre-step completions alike, with each
     * composite replaced by its arms. Pre-steps count because the panel's checklist evaluates
     * them.
     */
    public List<CompletionCondition> leafConditions() {
        List<CompletionCondition> leaves = new ArrayList<>();
        for (GuideStep step : allSteps()) {
            addLeaves(step.getCompletion(), leaves);
            if (step.getPreSteps() != null) {
                for (PreStep preStep : step.getPreSteps()) {
                    addLeaves(preStep.getCompletion(), leaves);
                }
            }
        }
        return leaves;
    }

    private static void addLeaves(CompletionCondition condition, List<CompletionCondition> leaves) {
        if (condition instanceof CompletionCondition.Composite) {
            for (CompletionCondition arm : ((CompletionCondition.Composite) condition).getConditions()) {
                addLeaves(arm, leaves);
            }
        } else if (condition != null) {
            leaves.add(condition);
        }
    }

    static List<GuideStep> flattenSteps(List<Chapter> chapters) {
        if (chapters == null) {
            return Collections.emptyList();
        }
        List<GuideStep> result = new ArrayList<>();
        for (Chapter chapter : chapters) {
            if (chapter.getSections() == null) continue;
            for (Section section : chapter.getSections()) {
                if (section.getEntries() == null) continue;
                for (Entry entry : section.getEntries()) {
                    if (entry.getSteps() == null) continue;
                    result.addAll(entry.getSteps());
                }
            }
        }
        return result;
    }

    static Map<String, Integer> buildStepPositionIndex(List<GuideStep> steps) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            index.put(steps.get(i).getId().getValue(), i);
        }
        return index;
    }

    /**
     * Each step's assumptions, in force from the step itself up to the {@code untilStep}.
     * An assumption whose {@code untilStep} the guide does not have, or does not place after
     * the host, is dropped rather than run to the end of the guide: a reminder that never
     * stops is worse than one that never starts. An assumption without a check is dropped too.
     */
    static List<AssumptionSpan> buildAssumptionSpans(List<GuideStep> steps,
                                                     Map<String, Integer> positionIndex) {
        List<AssumptionSpan> spans = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            List<StepAssumption> assumes = steps.get(i).getAssumes();
            if (assumes == null) {
                continue;
            }
            for (StepAssumption assumption : assumes) {
                if (assumption == null || assumption.getCheck() == null) {
                    continue;
                }
                int until = Integer.MAX_VALUE;
                if (assumption.getUntilStep() != null) {
                    Integer at = positionIndex.get(assumption.getUntilStep().getValue());
                    if (at == null || at <= i) {
                        continue;
                    }
                    until = at;
                }
                spans.add(new AssumptionSpan(i, until, assumption));
            }
        }
        return spans;
    }

    /**
     * The steps that set the hardwood advice, in guide order, each list copied immutable so a
     * snapshot can hand it out without a defensive copy. Null entries are dropped.
     */
    static List<HardwoodCarrier> buildHardwoodCarriers(List<GuideStep> steps) {
        List<HardwoodCarrier> carriers = new ArrayList<>();
        for (GuideStep step : steps) {
            if (step.getKingdomHardwood() == null) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String name : step.getKingdomHardwood()) {
                if (name != null) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                carriers.add(new HardwoodCarrier(step.getId(), List.copyOf(names)));
            }
        }
        return carriers;
    }

    static Map<StepId, StepContext> buildStepContextMap(List<Chapter> chapters) {
        if (chapters == null) {
            return Collections.emptyMap();
        }
        Map<StepId, StepContext> map = new HashMap<>();
        for (Chapter chapter : chapters) {
            if (chapter.getSections() == null) continue;
            for (Section section : chapter.getSections()) {
                if (section.getEntries() == null) continue;
                for (Entry entry : section.getEntries()) {
                    if (entry.getSteps() == null) continue;
                    StepContext ctx = new StepContext(
                            chapter.getTitle(),
                            section.getTitle(),
                            entry.getId(),
                            entry.getOriginalText(),
                            entry.isDurationEstimated() ? null : entry.getEstimatedDurationMinutes(),
                            entry.getItemsNeeded());
                    for (GuideStep step : entry.getSteps()) {
                        map.put(step.getId(), ctx);
                    }
                }
            }
        }
        return map;
    }
}
