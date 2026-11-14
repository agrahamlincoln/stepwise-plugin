package com.stepwise.engine;

import java.util.Optional;

/**
 * Tracks the player's current region with debounce logic.
 *
 * <p>A <em>change</em> is only emitted after the player has been in the new region for a
 * stable number of game ticks, so running along a border does not flap.</p>
 *
 * <p>The first region of a session is emitted at once. The login region has no border
 * flicker to settle, and holding it back would leave position readers blind until the
 * player crossed a boundary.</p>
 */
public class RegionTracker
{
	private static final int REGION_STABLE_TICKS = 2;

	private int lastRegionId = -1;
	private int pendingRegionId = -1;
	private int regionStableTicks = 0;

	/**
	 * Updates region tracking with the current region ID.
	 *
	 * @param currentRegionId the player's current region ID
	 * @return the region to announce — the first one this session, or a later one once the
	 *         player has held it long enough to count as a move — otherwise empty
	 */
	public Optional<Integer> update(int currentRegionId)
	{
		if (currentRegionId == lastRegionId)
		{
			pendingRegionId = -1;
			regionStableTicks = 0;
			return Optional.empty();
		}

		if (currentRegionId == pendingRegionId)
		{
			regionStableTicks++;
		}
		else
		{
			pendingRegionId = currentRegionId;
			regionStableTicks = 1;
		}

		// The first region of the session is emitted undebounced (see class doc).
		if (lastRegionId == -1 || regionStableTicks >= REGION_STABLE_TICKS)
		{
			lastRegionId = currentRegionId;
			pendingRegionId = -1;
			regionStableTicks = 0;
			return Optional.of(currentRegionId);
		}
		return Optional.empty();
	}

	/**
	 * Resets all tracking state. Call on login/logout/world hop.
	 */
	public void reset()
	{
		lastRegionId = -1;
		pendingRegionId = -1;
		regionStableTicks = 0;
	}
}
