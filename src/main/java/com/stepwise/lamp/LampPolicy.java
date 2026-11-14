package com.stepwise.lamp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which skill the guide puts XP lamps on: a schedule keyed to guide position, and exceptions
 * for particular lamp items. Immutable.
 */
public final class LampPolicy
{
	/** No rules: nothing is ever highlighted. */
	public static final LampPolicy EMPTY = new LampPolicy(List.of(), List.of());

	private final List<LampRule> schedule;
	private final List<LampRule> exceptions;

	public LampPolicy(List<LampRule> schedule, List<LampRule> exceptions)
	{
		this.schedule = List.copyOf(schedule);
		this.exceptions = List.copyOf(exceptions);
	}

	public List<LampRule> getSchedule()
	{
		return schedule;
	}

	public List<LampRule> getExceptions()
	{
		return exceptions;
	}

	public boolean isEmpty()
	{
		return schedule.isEmpty() && exceptions.isEmpty();
	}

	/**
	 * Step ids the policy names that {@code positionIndex} lacks. A rule naming one is never
	 * in force.
	 */
	public List<String> unknownSteps(Map<String, Integer> positionIndex)
	{
		List<String> unknown = new ArrayList<>();
		for (List<LampRule> rules : List.of(schedule, exceptions))
		{
			for (LampRule rule : rules)
			{
				for (String id : new String[]{rule.getFromStep(), rule.getUntilStep()})
				{
					if (id != null && !positionIndex.containsKey(id) && !unknown.contains(id))
					{
						unknown.add(id);
					}
				}
			}
		}
		return unknown;
	}
}
