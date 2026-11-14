package com.stepwise;

import net.runelite.api.GameState;

/**
 * Decides when the panel's welcome page takes the last session, from the game states the
 * plugin sees. Client thread only.
 */
final class LastSessionGate
{
	/**
	 * Whether the panel is showing a logged-in account's live card. Cleared when the last
	 * session replaces it and when the plugin restarts, never by a hop: a hop that ends at the
	 * login screen (a world that turned the player away, a connection lost mid-hop) still
	 * replaces the card.
	 */
	private boolean accountOnPanel;

	/**
	 * Whether the last session has been shown since start-up, so a failed login attempt,
	 * which also returns to the login screen, does not build it again.
	 */
	private boolean lastSessionShown;

	/**
	 * Whether the client is still short of a login: booting, at the login screen, or logging
	 * in. A plugin started then has no account to load and shows the last session instead.
	 */
	static boolean isBeforeLogin(GameState state)
	{
		return state == GameState.UNKNOWN
			|| state == GameState.STARTING
			|| state == GameState.LOGIN_SCREEN
			|| state == GameState.LOGIN_SCREEN_AUTHENTICATOR
			|| state == GameState.LOGGING_IN;
	}

	/** An account has loaded and the panel shows it. */
	void accountLoaded()
	{
		accountOnPanel = true;
	}

	/**
	 * Whether this game state change puts the last session on the welcome page. Only the
	 * login screen does: a hop leaves the panel on the account hopping, which comes straight
	 * back, and a failed login changes nothing already on show.
	 */
	boolean showsLastSessionOn(GameState state)
	{
		return state == GameState.LOGIN_SCREEN && (accountOnPanel || !lastSessionShown);
	}

	/** The welcome page has taken the last session, replacing any account on the panel. */
	void lastSessionShown()
	{
		accountOnPanel = false;
		lastSessionShown = true;
	}

	/** Back to start-up, for a plugin that is disabled and enabled again. */
	void reset()
	{
		accountOnPanel = false;
		lastSessionShown = false;
	}
}
