package com.stepwise.snapshot;

import lombok.Value;

/**
 * Evaluated pre-step for display in the panel. Carries the navigation
 * instruction and a single evaluated criterion showing whether the
 * player has arrived at the destination.
 */
@Value
public class PreStepSummary
{
	String instruction;
	CompletionCriterion criterion;
}
