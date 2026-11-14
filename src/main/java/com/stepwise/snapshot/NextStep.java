package com.stepwise.snapshot;

import java.util.List;
import lombok.Value;

@Value
public class NextStep {
    String stepId;
    String instruction;
    String chapter;
    String section;

    /** Icon hints for the step's completion: at most one for a plain condition, or one per
     *  distinct icon type among a composite's arms. Item and skill hints use the generic
     *  "hasItem" and "skills" icons, with no key. */
    List<IconHint> iconHints;

    @Value
    public static class IconHint {
        String iconType;
        String iconKey;
    }
}
