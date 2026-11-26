package com.stepwise.engine;

import com.stepwise.guide.CompletionCondition;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepId;
import com.stepwise.model.PlayerState;
import com.stepwise.snapshot.DashboardSnapshot;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * What the Step Navigator draws from an engine, copied out of it: the current and previewed
 * steps, the skipped steps and the steps that count as finished. Built on the thread that
 * drives the engine, so the panel reads it on the EDT while the engine moves on.
 */
@EqualsAndHashCode
public final class NavigatorState
{
	/** The player's current step, or null. */
	@Getter
	@Nullable
	private final String currentStepId;
	/** The step the panel previews in its place, or null. */
	@Getter
	@Nullable
	private final String previewedStepId;
	private final Set<StepId> skipped;
	private final Set<StepId> finished;

	private NavigatorState(String currentStepId, String previewedStepId, Set<StepId> skipped,
		Set<StepId> finished)
	{
		this.currentStepId = currentStepId;
		this.previewedStepId = previewedStepId;
		this.skipped = skipped;
		this.finished = finished;
	}

	/** Call on the thread that drives {@code engine}. */
	public static NavigatorState of(CoreEngine engine)
	{
		Set<StepId> skipped = new HashSet<>(engine.skippedSteps);
		Set<StepId> finished = new HashSet<>(skipped);
		for (StepId id : engine.getCompletedSteps())
		{
			GuideStep step = engine.stepIndex.get(id);
			if (step == null || !durableUnmet(engine, step))
			{
				finished.add(id);
			}
		}
		return new NavigatorState(stepIdOf(engine.getLastSnapshot()),
			stepIdOf(engine.getPreviewSnapshot()), skipped, finished);
	}

	private static String stepIdOf(DashboardSnapshot snapshot)
	{
		return snapshot == null || snapshot.getGuidePosition() == null
			? null : snapshot.getGuidePosition().getStepId();
	}

	public boolean isSkipped(StepId id)
	{
		return skipped.contains(id);
	}

	/**
	 * Whether the step counts as finished: skipped, or marked done with the game agreeing
	 * wherever it can tell. A done mark does not count when the step's condition is one the
	 * game keeps for good, such as a quest, a level or a diary task, and the game reads it as
	 * unmet.
	 */
	public boolean isFinished(StepId id)
	{
		return finished.contains(id);
	}

	/** Whether {@code step} has a durable condition the game says is not met. */
	private static boolean durableUnmet(CoreEngine engine, GuideStep step)
	{
		PlayerState state = engine.getPlayerState();
		return state != null && ResolutionEngine.isMonotonic(step.getCompletion())
			&& allVarsRead(step.getCompletion(), state)
			&& !ResolutionEngine.evaluateCondition(step.getCompletion(), state,
				engine.getCompletedSteps());
	}

	/**
	 * Whether the state holds a reading for every var the condition checks. A var the saved
	 * state holds no reading for is unknown, not false: the login scan reads it live.
	 */
	static boolean allVarsRead(CompletionCondition condition, PlayerState state)
	{
		if (condition instanceof CompletionCondition.VarbitValue)
		{
			return state.getVarbits().containsKey(((CompletionCondition.VarbitValue) condition).getVarbitId());
		}
		if (condition instanceof CompletionCondition.VarpBitValue)
		{
			return state.getVarps().containsKey(((CompletionCondition.VarpBitValue) condition).getVarpId());
		}
		if (condition instanceof CompletionCondition.VarpValue)
		{
			return state.getVarps().containsKey(((CompletionCondition.VarpValue) condition).getVarp());
		}
		if (condition instanceof CompletionCondition.Composite)
		{
			for (CompletionCondition sub : ((CompletionCondition.Composite) condition).getConditions())
			{
				if (!allVarsRead(sub, state))
				{
					return false;
				}
			}
		}
		return true;
	}
}
