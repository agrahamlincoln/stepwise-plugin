package com.stepwise;

import com.google.gson.Gson;
import com.stepwise.engine.CoreEngine;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.MilestonesFile;
import com.stepwise.persistence.ConfigStore;
import com.stepwise.persistence.StepwisePersistence;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.client.config.RuneScapeProfile;
import net.runelite.client.config.RuneScapeProfileType;

/**
 * Builds an engine over the last account's saved progress, for the panel to show while
 * nobody is logged in.
 *
 * <p>A separate engine from the plugin's, so showing the last account never touches the live
 * engine's state or moves the snapshot version the panel waits on.
 *
 * <p>Pass a store from {@link ConfigStore#readOnly}. Building the engine runs code that
 * writes (loading saves the guide version, a resolve can latch steps), and the read-only
 * store drops every one of those writes, so the account's saved state is never changed.
 */
public final class LastSession
{
	private LastSession()
	{
	}

	/**
	 * The RS profile holding {@code playerName}'s saved progress, for a session summary that
	 * carries no profile key.
	 *
	 * <p>One account owns a profile per world type, and a renamed account's old profiles keep
	 * the old name. The profiles with saved progress decide; when they all belong to one
	 * account, its standard-world one. Anything less certain returns null, because choosing
	 * between profiles could show another account's progress under this name.
	 *
	 * @param hasProgress whether a profile key holds saved progress
	 * @return the profile key, or null when no single profile fits
	 */
	public static String profileNamed(String playerName, List<RuneScapeProfile> profiles,
		Predicate<String> hasProgress)
	{
		if (playerName == null)
		{
			return null;
		}
		List<RuneScapeProfile> saved = new ArrayList<>();
		for (RuneScapeProfile profile : profiles)
		{
			if (playerName.equals(profile.getDisplayName()) && hasProgress.test(profile.getKey()))
			{
				saved.add(profile);
			}
		}
		if (saved.size() == 1)
		{
			return saved.get(0).getKey();
		}
		for (RuneScapeProfile profile : saved)
		{
			// Two accounts have held this name, and nothing says which one played last.
			long account = saved.get(0).getAccountHash();
			if (account == RuneScapeProfile.ACCOUNT_HASH_INVALID || profile.getAccountHash() != account)
			{
				return null;
			}
		}
		String standard = null;
		for (RuneScapeProfile profile : saved)
		{
			if (profile.getType() == RuneScapeProfileType.STANDARD)
			{
				if (standard != null)
				{
					return null;
				}
				standard = profile.getKey();
			}
		}
		return standard;
	}

	/**
	 * The account's progress as the engine would resolve it at login, from saved state
	 * alone.
	 *
	 * @param store a read-only store over the account's RS profile
	 * @return the engine, or null when the store holds no saved player state
	 */
	public static CoreEngine build(StepwiseGuide guide, MilestonesFile milestones,
		ConfigStore store, Gson gson)
	{
		StepwisePersistence persistence = new StepwisePersistence(store, gson);
		CoreEngine engine = new CoreEngine(guide, persistence, milestones);
		engine.loadPersistedState();
		if (engine.getPlayerState() == null)
		{
			return null;
		}
		// Login primes the kingdom once its scan has written the kingdom's varbits. Here the
		// saved varbits stand in for that scan.
		engine.primeKingdomFromLogin();
		return engine;
	}
}
