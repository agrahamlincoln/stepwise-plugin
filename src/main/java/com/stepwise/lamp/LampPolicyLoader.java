package com.stepwise.lamp;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.stepwise.guide.GuideLoader;
import com.stepwise.model.Skill;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads {@code lamp_policy.json} from plugin resources.
 *
 * <p>Never throws and never returns null: a missing, unreadable or malformed file yields
 * {@link LampPolicy#EMPTY}, and the plugin then highlights nothing on XP lamps. A missing
 * file logs a warning. A rule that names an unknown skill, lacks a required field, or puts
 * {@code untilStep} on a schedule entry is dropped on its own.
 */
@Slf4j
public final class LampPolicyLoader
{
	static final String RESOURCE = "/com/stepwise/lamp_policy.json";

	private LampPolicyLoader()
	{
	}

	public static LampPolicy loadFromResource(Gson gson)
	{
		return loadFromResource(RESOURCE, gson);
	}

	static LampPolicy loadFromResource(String resourcePath, Gson gson)
	{
		return GuideLoader.readResource(resourcePath, reader -> parse(reader, gson), () -> LampPolicy.EMPTY);
	}

	static LampPolicy parse(Reader reader, Gson gson)
	{
		RawPolicy raw;
		try
		{
			raw = gson.fromJson(reader, RawPolicy.class);
		}
		catch (JsonParseException e)
		{
			log.warn("Failed to parse lamp_policy.json; XP lamp highlight disabled", e);
			return LampPolicy.EMPTY;
		}
		if (raw == null)
		{
			log.warn("lamp_policy.json is empty; XP lamp highlight disabled");
			return LampPolicy.EMPTY;
		}
		List<LampRule> schedule = freeze(raw.schedule, false);
		List<LampRule> exceptions = freeze(raw.exceptions, true);
		log.debug("Loaded lamp policy: {} schedule entries, {} exceptions",
			schedule.size(), exceptions.size());
		return new LampPolicy(schedule, exceptions);
	}

	private static List<LampRule> freeze(List<RawRule> rawRules, boolean exception)
	{
		List<LampRule> rules = new ArrayList<>();
		if (rawRules == null)
		{
			return rules;
		}
		for (RawRule raw : rawRules)
		{
			LampRule rule = raw == null ? null : freeze(raw, exception);
			if (rule != null)
			{
				rules.add(rule);
			}
		}
		return rules;
	}

	private static LampRule freeze(RawRule raw, boolean exception)
	{
		Skill skill = skill(raw.skill);
		Skill then = skill(raw.then);
		Skill fallback = skill(raw.fallback);
		int untilLevel = raw.untilLevel != null ? raw.untilLevel : 0;
		boolean levelSwitchHalfSet = (untilLevel > 0) != (raw.then != null);
		boolean badSkill = skill == null
			|| (raw.then != null && then == null)
			|| (raw.fallback != null && fallback == null);
		// A schedule entry runs until the next one starts, so an untilStep on it is a mistake.
		boolean badTrigger = exception
			? raw.itemIds == null || raw.itemIds.isEmpty() || raw.itemIds.contains(null)
			: raw.fromStep == null || raw.untilStep != null;
		if (badSkill || levelSwitchHalfSet || badTrigger)
		{
			log.warn("lamp_policy.json: dropping unusable rule (skill {}, from {}, items {})",
				raw.skill, raw.fromStep, raw.itemIds);
			return null;
		}
		return new LampRule(
			raw.fromStep,
			raw.untilStep,
			skill,
			untilLevel,
			then,
			fallback,
			raw.note != null ? raw.note : "",
			exception ? Set.copyOf(raw.itemIds) : Set.of());
	}

	private static Skill skill(String name)
	{
		if (name == null)
		{
			return null;
		}
		try
		{
			return Skill.fromString(name);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** The file's shape, as written; checked and frozen into {@link LampRule}s. */
	private static final class RawPolicy
	{
		List<RawRule> schedule;
		List<RawRule> exceptions;
	}

	private static final class RawRule
	{
		String fromStep;
		String untilStep;
		String skill;
		Integer untilLevel;
		String then;
		String fallback;
		String note;
		List<Integer> itemIds;
	}
}
