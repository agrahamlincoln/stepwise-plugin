package com.stepwise;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;

/**
 * Stepwise's settings.
 */
@ConfigGroup("stepwise")
public interface StepwiseConfig extends Config
{
	@ConfigSection(
		name = "Step Overlay",
		description = "The current step drawn over the game",
		position = 10
	)
	String stepOverlaySection = "stepOverlay";

	@ConfigSection(
		name = "In-Game Helpers",
		description = "Highlights and InfoBoxes in the game view",
		position = 20
	)
	String helpersSection = "helpers";

	@ConfigSection(
		name = "Notifications",
		description = "Alerts when a game event finishes your current step",
		position = 30
	)
	String notificationsSection = "notifications";

	@ConfigItem(
		keyName = "showStepOverlay",
		name = "Show Step Overlay",
		description = "Show the current step as an in-game overlay. Hold the drag hotkey (Alt by default)"
			+ " to move or resize it.",
		section = stepOverlaySection,
		position = 11
	)
	default boolean showStepOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightLampSkill",
		name = "Highlight the Guide's Skill on XP Lamps",
		description = "Outline the skill the guide recommends when an XP lamp, tome or book asks"
			+ " you to choose one.",
		section = helpersSection,
		position = 21
	)
	default boolean highlightLampSkill()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showBirdhouseInfoBox",
		name = "Show Bird House InfoBox",
		description = "Count down your bird house run in an InfoBox while any house is seeded.",
		section = helpersSection,
		position = 22
	)
	default boolean showBirdhouseInfoBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyStepComplete",
		name = "Step Complete",
		description = "Notify when a game event finishes your current step. Steps in the In Progress"
			+ " list, and steps you finished before reaching them, are checked off silently.",
		section = notificationsSection,
		position = 31
	)
	default Notification notifyStepComplete()
	{
		return Notification.OFF;
	}
}
