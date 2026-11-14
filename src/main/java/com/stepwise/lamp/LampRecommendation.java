package com.stepwise.lamp;

import com.stepwise.model.Skill;
import lombok.Value;

/** The skill the guide recommends for one XP chooser session, and why. */
@Value
public class LampRecommendation
{
	Skill skill;
	/** Highlighted instead when {@link #skill} is faded on this lamp; null when unset. */
	Skill fallback;
	String note;
}
