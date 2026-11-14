package com.stepwise.snapshot;

import com.stepwise.guide.StepId;
import com.stepwise.guide.StepStatus;
import lombok.Value;

@Value
public class ResolvedStep {
    StepId stepId;
    StepStatus status;
}
