package com.stepwise.snapshot;

import java.util.Locale;
import lombok.Value;

@Value
public class GuidePosition {
    String stepId;
    String instruction;
    String chapter;
    String section;
    int stepIndex;
    int totalSteps;

    /**
     * The step counter the panel and the overlay both show, "Step 1,234 / 1,649", with
     * thousands grouped by commas whatever the client's locale.
     */
    public String stepCounter() {
        return String.format(Locale.ROOT, "Step %,d / %,d", stepIndex + 1, totalSteps);
    }
}
