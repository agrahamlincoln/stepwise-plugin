package com.stepwise.lamp;

import com.stepwise.model.Skill;
import net.runelite.api.gameval.InterfaceID;

/** Maps each skill to its button layer on the XP reward chooser (interface 240). */
public final class XpRewardSkills
{
	private static final int[] COMPONENTS = new int[Skill.values().length];

	static
	{
		put(Skill.ATTACK, InterfaceID.Xpreward.ATTACK);
		put(Skill.DEFENCE, InterfaceID.Xpreward.DEFENCE);
		put(Skill.STRENGTH, InterfaceID.Xpreward.STRENGTH);
		put(Skill.HITPOINTS, InterfaceID.Xpreward.HITPOINTS);
		put(Skill.RANGED, InterfaceID.Xpreward.RANGED);
		put(Skill.PRAYER, InterfaceID.Xpreward.PRAYER);
		put(Skill.MAGIC, InterfaceID.Xpreward.MAGIC);
		put(Skill.COOKING, InterfaceID.Xpreward.COOKING);
		put(Skill.WOODCUTTING, InterfaceID.Xpreward.WOODCUTTING);
		put(Skill.FLETCHING, InterfaceID.Xpreward.FLETCHING);
		put(Skill.FISHING, InterfaceID.Xpreward.FISHING);
		put(Skill.FIREMAKING, InterfaceID.Xpreward.FIREMAKING);
		put(Skill.CRAFTING, InterfaceID.Xpreward.CRAFTING);
		put(Skill.SMITHING, InterfaceID.Xpreward.SMITHING);
		put(Skill.MINING, InterfaceID.Xpreward.MINING);
		put(Skill.HERBLORE, InterfaceID.Xpreward.HERBLORE);
		put(Skill.AGILITY, InterfaceID.Xpreward.AGILITY);
		put(Skill.THIEVING, InterfaceID.Xpreward.THIEVING);
		put(Skill.SLAYER, InterfaceID.Xpreward.SLAYER);
		put(Skill.FARMING, InterfaceID.Xpreward.FARMING);
		put(Skill.RUNECRAFT, InterfaceID.Xpreward.RUNECRAFT);
		put(Skill.HUNTER, InterfaceID.Xpreward.HUNTER);
		put(Skill.CONSTRUCTION, InterfaceID.Xpreward.CONSTRUCTION);
		put(Skill.SAILING, InterfaceID.Xpreward.SAILING);
	}

	private XpRewardSkills()
	{
	}

	private static void put(Skill skill, int componentId)
	{
		COMPONENTS[skill.ordinal()] = componentId;
	}

	/** The chooser's button layer for {@code skill}. */
	public static int componentId(Skill skill)
	{
		return COMPONENTS[skill.ordinal()];
	}
}
