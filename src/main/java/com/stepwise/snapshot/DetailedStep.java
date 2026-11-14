package com.stepwise.snapshot;

import lombok.Value;
import java.util.List;

@Value
public class DetailedStep {
    String stepId;
    String instruction;
    String guidance;
    String chapter;
    String section;
    /** Occasion label for background steps ("Next farm run"); null when the step has none. */
    String when;
    List<CompletionCriterion> criteria;
    List<PreStepSummary> preSteps;
    /** Whether nothing the game reports at this step completes it; Mark Done is the expected action. */
    boolean completedByClick;
    /** Whether the player skipped the step rather than completed it. Set on Recent rows only. */
    boolean skipped;
}
