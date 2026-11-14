package com.stepwise.lamp;

import com.stepwise.model.Skill;
import java.util.Set;
import lombok.Value;

/**
 * One line of the lamp policy: a schedule entry, or an exception for particular lamp items.
 *
 * <p>Step ids stay strings because the policy is matched against the guide's position index,
 * which is keyed by string. A step the loaded guide does not have is never reached, so a rule
 * naming one is never in force.
 */
@Value
public class LampRule
{
	/** First guide position the rule applies at (inclusive); null on an exception with no lower bound. */
	String fromStep;
	/** Guide position the rule stops applying at (exclusive); null when it never stops. */
	String untilStep;
	Skill skill;
	/** Real level in {@link #skill} at which {@link #then} takes over; 0 when unset. */
	int untilLevel;
	/** The skill once {@link #skill} reaches {@link #untilLevel}; null when unset. */
	Skill then;
	/** Highlighted instead when the chosen skill is faded on this lamp; null when unset. */
	Skill fallback;
	/** Tooltip text naming the guide's reason. */
	String note;
	/** Lamp, tome or book item ids an exception matches; empty for a schedule entry. */
	Set<Integer> itemIds;

	/** Whether a step bound limits where in the guide this exception applies. */
	public boolean isWindowed()
	{
		return fromStep != null || untilStep != null;
	}

	/**
	 * The skill this rule picks for a player with {@code realLevel} in {@link #skill}.
	 */
	public Skill skillAt(int realLevel)
	{
		return then != null && untilLevel > 0 && realLevel >= untilLevel ? then : skill;
	}
}
