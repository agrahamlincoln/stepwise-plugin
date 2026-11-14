package com.stepwise.lamp;

import com.stepwise.guide.RoutePosition;
import com.stepwise.guide.StepId;
import com.stepwise.model.Skill;
import java.util.Collection;
import java.util.Map;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;

/**
 * Picks the guide's skill for an XP chooser session from the lamp policy. Pure: everything it
 * reads arrives as an argument, so it runs without a client.
 *
 * <p>Exceptions win over the schedule. An exception matches the item the player used to open
 * the chooser, inside its optional step window ({@code fromStep} inclusive, {@code untilStep}
 * exclusive, by guide order). When two match, a windowed exception beats an unwindowed one as
 * the more specific; among equals the first in the file wins. Otherwise the schedule entry in
 * force is the one with the latest {@code fromStep} at or before the player's guide position
 * ({@link RoutePosition}).
 */
public final class LampAdvisor
{
	private LampAdvisor()
	{
	}

	/**
	 * @param positionIndex step id to guide order, from the loaded guide
	 * @param activeStepId  the step the player is on, or null when none is active
	 * @param completed     completed steps
	 * @param realLevel     the player's real (unboosted) level in a skill
	 * @param itemId        the item used to open the chooser, or -1 when unknown
	 * @return the recommendation, or null when no rule is in force
	 */
	@Nullable
	public static LampRecommendation recommend(
		LampPolicy policy,
		Map<String, Integer> positionIndex,
		@Nullable String activeStepId,
		Collection<StepId> completed,
		ToIntFunction<Skill> realLevel,
		int itemId)
	{
		int position = RoutePosition.of(positionIndex, activeStepId, completed);

		LampRule match = null;
		for (LampRule rule : policy.getExceptions())
		{
			if (itemId > 0
				&& rule.getItemIds().contains(itemId)
				&& inWindow(rule, positionIndex, position)
				&& (match == null || (rule.isWindowed() && !match.isWindowed())))
			{
				match = rule;
			}
		}
		if (match != null)
		{
			return recommendation(match, realLevel);
		}

		LampRule inForce = null;
		int inForceAt = RoutePosition.NONE;
		for (LampRule rule : policy.getSchedule())
		{
			Integer from = positionIndex.get(rule.getFromStep());
			if (from != null && from <= position && from >= inForceAt)
			{
				inForce = rule;
				inForceAt = from;
			}
		}
		return inForce != null ? recommendation(inForce, realLevel) : null;
	}

	/**
	 * Whether {@code position} lies in the rule's window, from {@code fromStep} up to but not
	 * including {@code untilStep}. A bound the guide does not have closes the window.
	 */
	private static boolean inWindow(LampRule rule, Map<String, Integer> positionIndex, int position)
	{
		if (rule.getFromStep() != null)
		{
			Integer from = positionIndex.get(rule.getFromStep());
			if (from == null || position < from)
			{
				return false;
			}
		}
		if (rule.getUntilStep() != null)
		{
			Integer until = positionIndex.get(rule.getUntilStep());
			return until != null && position < until;
		}
		return true;
	}

	private static LampRecommendation recommendation(LampRule rule, ToIntFunction<Skill> realLevel)
	{
		Skill skill = rule.skillAt(realLevel.applyAsInt(rule.getSkill()));
		return new LampRecommendation(skill, rule.getFallback(), rule.getNote());
	}
}
