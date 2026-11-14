package com.stepwise.lamp;

import java.util.Locale;
import java.util.Set;
import net.runelite.client.util.Text;

/**
 * Remembers which lamp, tome or book the player used to open the XP chooser.
 *
 * <p>Only the player's own click on an inventory item with an option that opens the chooser
 * counts, and only when the chooser opens within {@link #CAPTURE_TICKS} of it. The server
 * answers the click on its next tick or so, and the window leaves room for a few laggy ticks
 * but not for a conversation. A chooser an NPC hands over in dialogue after a lamp was rubbed
 * therefore opens with no item and gets the schedule. Closing the chooser forgets the item as
 * well, and one click explains one chooser at most.
 *
 * <p>Pure: callers pass the game tick, so it runs without a client.
 */
public final class LampChooserTracker
{
	/** The most ticks between the click and the chooser opening, 3 seconds. */
	static final int CAPTURE_TICKS = 5;

	/**
	 * Inventory options that open the chooser, lowercased: lamps are rubbed, tomes and books
	 * read, the blessed lamp prayed over, the dark relic communed with.
	 */
	private static final Set<String> CHOOSER_OPTIONS = Set.of("rub", "read", "pray-over", "commune");

	private static final int NONE = -1;

	private int clickedItemId = NONE;
	private int clickedTick;
	private boolean open;
	private int sessionItemId = NONE;

	/**
	 * The player chose {@code option} on inventory item {@code itemId}. Remembered only when
	 * the option is one that opens the chooser; any other click leaves the memory alone.
	 */
	public void onItemOp(int itemId, String option, int tick)
	{
		if (itemId > 0 && option != null
			&& CHOOSER_OPTIONS.contains(Text.removeTags(option).toLowerCase(Locale.ROOT)))
		{
			clickedItemId = itemId;
			clickedTick = tick;
		}
	}

	/**
	 * The chooser opened.
	 *
	 * @return the item that opened it, or -1 when no click just before explains it
	 */
	public int onChooserOpened(int tick)
	{
		if (open)
		{
			return sessionItemId;
		}
		open = true;
		int age = tick - clickedTick;
		sessionItemId = clickedItemId != NONE && age >= 0 && age <= CAPTURE_TICKS ? clickedItemId : NONE;
		clickedItemId = NONE;
		return sessionItemId;
	}

	/**
	 * The chooser's interface closed. Only an unload ends the session: a close without one
	 * moves the interface (a fixed/resizable layout switch) and is followed by a fresh load.
	 *
	 * @return whether a session ended, so the caller clears the highlight
	 */
	public boolean onChooserClosed(boolean unload)
	{
		if (!unload)
		{
			return false;
		}
		boolean wasOpen = open;
		endSession();
		return wasOpen;
	}

	/**
	 * A game tick passed.
	 *
	 * @param chooserPresent whether the chooser's interface is still loaded
	 * @return whether this ended a session the close event never reported
	 */
	public boolean onGameTick(boolean chooserPresent)
	{
		if (open && !chooserPresent)
		{
			endSession();
			return true;
		}
		return false;
	}

	public boolean isOpen()
	{
		return open;
	}

	/** Forget everything, for a logout or a plugin restart. */
	public void reset()
	{
		endSession();
		clickedTick = 0;
	}

	private void endSession()
	{
		open = false;
		sessionItemId = NONE;
		clickedItemId = NONE;
	}
}
