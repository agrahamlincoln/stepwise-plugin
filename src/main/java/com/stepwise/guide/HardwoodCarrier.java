package com.stepwise.guide;

import java.util.List;
import lombok.Value;

/** A step that sets the Miscellania hardwood advice, with its list copied immutable at load. */
@Value
public class HardwoodCarrier {
    StepId stepId;
    /** Hardwood labels, first choice first; never empty and never holding null. */
    List<String> hardwoods;
}
