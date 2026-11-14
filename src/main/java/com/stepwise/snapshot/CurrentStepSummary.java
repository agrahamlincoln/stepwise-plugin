package com.stepwise.snapshot;

import java.util.List;
import lombok.Value;

/** The current guide step as the step card and the overlay draw it. */
@Value
public class CurrentStepSummary {
    String stepId;
    String instruction;
    String guidance;
    List<CompletionCriterion> criteria;
    /**
     * The entry's items-needed list, each row's ownership resolved against the
     * current player state, or null when the guide lists none.
     */
    List<ItemNeededStatus> itemsNeeded;
    List<PreStepSummary> preSteps;
    /**
     * Whether {@link #criteria} are alternatives — any one of them completes the step —
     * rather than a list that must all be met. Only true when there is more than one
     * row to choose between; a labelled composite that draws as one row is neither.
     */
    boolean criteriaAlternatives;
    /**
     * Whether nothing the game reports at this step completes it; Mark Done is the expected
     * action. Not the same as having no criteria to draw: an all-of with a manual arm draws
     * its other arms and still waits on the click.
     */
    boolean completedByClick;
    /** The step's "From now on" callout, or null when it has none. */
    String fromNowOn;
    /**
     * Route assumptions in force that read unmet, in guide order, and only once the bank has
     * been seen (see {@code SnapshotBuilder.assumptionNudges}). Never null.
     */
    List<AssumptionNudge> assumptionNudges;
}
