package com.stepwise;

import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.google.inject.Provides;
import com.stepwise.engine.BirdhouseTracker;
import com.stepwise.engine.BowStringSpool;
import com.stepwise.engine.CoreEngine;
import com.stepwise.engine.RunePouch;
import com.stepwise.engine.EventApplier;
import com.stepwise.engine.EventParser;
import com.stepwise.engine.ItemClassResolver;
import com.stepwise.engine.KingdomTracker;
import com.stepwise.engine.PlankSack;
import com.stepwise.engine.RegionTracker;
import com.stepwise.engine.ResolutionEngine;
import com.stepwise.engine.SeedBoxListing;
import com.stepwise.engine.SlayerTaskTracker;
import com.stepwise.engine.SnapshotBuilder;
import com.stepwise.engine.TearsOfGuthixTracker;
import com.stepwise.guide.BudgetLoader;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.GuideLoader;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.MilestoneLoader;
import com.stepwise.guide.MilestonesFile;
import com.stepwise.guide.NpcInteractions;
import com.stepwise.guide.RequiredVarIds;
import com.stepwise.lamp.LampAdvisor;
import com.stepwise.lamp.LampChooserTracker;
import com.stepwise.lamp.LampHighlightOverlay;
import com.stepwise.lamp.LampPolicy;
import com.stepwise.lamp.LampPolicyLoader;
import com.stepwise.lamp.LampRecommendation;
import com.stepwise.quest.QuestStageData;
import com.stepwise.model.AccountType;
import com.stepwise.model.EquipmentSlot;
import com.stepwise.model.ItemStack;
import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import com.stepwise.overlay.BirdhouseInfoBox;
import com.stepwise.sailing.BoatVarbits;
import com.stepwise.sailing.CargoHolds;
import com.stepwise.overlay.StepOverlay;
import com.stepwise.panel.StepwisePanel;
import com.stepwise.panel.TextMarkup;
import com.stepwise.panel.IconProvider;
import com.stepwise.persistence.ConfigStore;
import com.stepwise.persistence.StepwisePersistence;
import com.stepwise.quest.QuestVarbits;
import com.stepwise.snapshot.DashboardSnapshot;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Stepwise: BRUHsailer Guide",
	description = "Iron guide, step by step. Follows the BRUHsailer Ironman guide by So Iron BRUH and ParasailerOSRS, checks steps off as you play, and finds your place on your first login.",
	tags = {"bruhsailer", "bruhsailor", "ironman", "guide", "efficiency", "route", "steps", "checklist",
		"tracker", "progression", "quests"}
)
public class StepwisePlugin extends Plugin
{
	/**
	 * The item containers read besides the boats' cargo holds. The tackle box, seed vault and
	 * cargo holds are reported only while open. The game does not send the carried seed box's
	 * container; the box is read from its Check listing in chat.
	 */
	private static final Set<Integer> TRACKED_CONTAINERS = Set.of(InventoryID.INV,
		InventoryID.WORN, InventoryID.BANK, InventoryID.TACKLE_BOX, InventoryID.SEED_VAULT);

	/**
	 * The account type varbit's values in order (0 normal through 6 unranked group ironman);
	 * any other value is read as a normal account.
	 */
	private static final AccountType[] ACCOUNT_TYPES = {AccountType.NORMAL, AccountType.IRONMAN,
		AccountType.ULTIMATE_IRONMAN, AccountType.HARDCORE_IRONMAN, AccountType.GROUP_IRONMAN,
		AccountType.HARDCORE_GROUP_IRONMAN, AccountType.UNRANKED_GROUP_IRONMAN};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private StepwiseConfig config;

	@Inject
	private Notifier notifier;

	@Inject
	private Gson gson;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private StepOverlay stepOverlay;

	@Inject
	private LampHighlightOverlay lampOverlay;

	/** RuneLite's shared background executor, for work too slow for the client thread. */
	@Inject
	private ScheduledExecutorService executor;

	private StepwisePanel stepwisePanel;
	private IconProvider iconProvider;
	private NavigationButton navButton;

	/**
	 * Volatile: written in {@code startUp} and {@code shutDown}, and read from the client
	 * thread. An unsafely published engine can be seen half-constructed.
	 */
	private volatile CoreEngine coreEngine;

	private final int[] previousXp = new int[Skill.values().length];

	/** Each container's last contents as id/quantity pairs, so a report with nothing moved is a no-op. */
	private final Map<Integer, int[]> containerContents = new HashMap<>();

	/**
	 * Set by any var change, which is the only way a quest's state moves, so the tick polls
	 * quest states only after one.
	 */
	private boolean questStatesDirty;

	/**
	 * Item ids by lower-case name, for every item seen in a container this session.
	 *
	 * <p>Exists for the seed box, whose chat lines ("Stored 6 x Avantoe seed in your seed
	 * box") carry no id. Seeds being boxed were in the bag or bank moments earlier, so this
	 * has seen them by the time the line arrives.
	 *
	 * <p>Keys are canonical names, so a noted stack resolves to its unnoted item. Bank
	 * placeholders are excluded: they carry the real item's name against their own id, which
	 * the player owns none of. Not persisted.
	 */
	private final Map<String, Integer> itemIdsByName = new HashMap<>();

	/**
	 * The Check listing being read right now, or null when none is open.
	 *
	 * <p>A listing arrives as one chat message per seed with no closing line. It ends at the
	 * first message that is not one of its entries, or at the end of the tick. Held here
	 * because {@code EventParser} is stateless and reads one line at a time.
	 */
	private SeedBoxListing seedBoxListing;

	private final Map<Quest, QuestState> previousQuestStates = new LinkedHashMap<>();

	private final RegionTracker regionTracker = new RegionTracker();

	// The last quest var value applied per quest name, so a repeated value is applied once.
	private final Map<String, Integer> lastQuestVarValues = new HashMap<>();

	// The deposit box sends no bank contents. The inventory is snapshotted when the box opens,
	// and what leaves it while the box is open is counted as deposited.
	private boolean depositBoxOpen = false;
	private Map<String, Integer> depositBoxInventorySnapshot;

	/** The bundled guide, null while stopped or when it failed to load. */
	private StepwiseGuide guide;
	private RequiredVarIds requiredVarIds;
	/** Null when the bundled milestones failed to load. */
	private MilestonesFile milestonesFile;
	/** Empty, never null, while started: a missing policy highlights nothing. */
	private LampPolicy lampPolicy = LampPolicy.EMPTY;
	private final LampChooserTracker lampChooser = new LampChooserTracker();

	private final LastSessionGate lastSessionGate = new LastSessionGate();

	/** The last session being built on the executor, or null; cancelled at shutdown. */
	private Future<?> lastSessionBuild;

	/**
	 * Between startUp and shutDown. Volatile: the last session's build reads it on the
	 * executor, to tell a failure from a shutdown pulling the statics it resolves against.
	 */
	private volatile boolean started;

	// Login readiness gate
	private boolean loginReady = false;
	// Logged once per session, not again when a progress reset re-runs the login snapshot.
	private boolean loginQuestProgressLogged = false;
	private boolean loginSnapshotPending = false;
	private int loginTickCounter = 0;
	private static final int LOGIN_READY_MAX_TICKS = 10;
	/** Loads tried per login before tracking pauses for the session; see readAccount. */
	private static final int LOGIN_LOAD_ATTEMPTS = 3;
	/** Loads that have thrown since the last login, or since the last load that worked. */
	private int loginLoadFailures;
	// Juna's Tears of Guthix eligibility line, held until the session is armed. Chat is
	// dispatched ahead of the tick that opens the login gate, so the chat handler's own
	// gate would drop it, and it is the only message reporting that eligibility.
	private String pendingTearsOfGuthixLine = null;

	/**
	 * Whether a session on these worlds is the account's real progress.
	 *
	 * <p>PvP Arena worlds hand every player a preset loadout with most quests, stats and
	 * spellbooks unlocked. Reading that as progress latches hundreds of steps the account has
	 * never done. Because the engine outlives a hop, those latches would then merge into the
	 * real profile's progress on the way back. RuneLite keeps a separate profile for these
	 * worlds, but that only separates storage, not the in-memory engine, so the engine must
	 * never load or latch on these worlds.
	 *
	 * @param worldTypes the current world's types, from {@link Client#getWorldType()}
	 * @return false when the session must leave the guide untouched, true otherwise
	 */
	static boolean tracksProgressOn(Set<WorldType> worldTypes)
	{
		return !worldTypes.contains(WorldType.PVP_ARENA);
	}

	@Override
	protected void startUp()
	{
		started = true;
		try
		{
			guide = GuideLoader.loadFromResource(gson);
			log.debug("Loaded guide: {} ({} steps)", guide.getTitle(), guide.allSteps().size());
		}
		catch (Exception e)
		{
			log.error("Failed to load guide — Stepwise will not function", e);
			return;
		}
		requiredVarIds = RequiredVarIds.fromGuide(guide);

		// Load milestones (optional — auto-latching disabled if absent)
		milestonesFile = MilestoneLoader.loadFromResource(gson);

		QuestStageData questStageData = QuestStageData.loadFromResource(gson);
		SnapshotBuilder.setQuestStageData(questStageData);

		ResolutionEngine.initItemClasses(ItemClassResolver.loadFromResource(gson));

		// Optional: without it the panel only loses its comparison against the route's plan.
		SnapshotBuilder.setGuideBudget(BudgetLoader.loadFromResource(gson));

		// Optional: without it XP lamps get no highlight.
		lampPolicy = LampPolicyLoader.loadFromResource(gson);
		for (String unknown : lampPolicy.unknownSteps(guide.stepPositionIndex()))
		{
			log.warn("lamp_policy.json names step {}, which the guide does not have", unknown);
		}

		// Persisted state loads in readAccount(), once the RS profile is available.
		persistence = new StepwisePersistence(ConfigStore.of(configManager), gson);
		coreEngine = new CoreEngine(guide, persistence, milestonesFile);
		coreEngine.setClientThreadExecutor(clientThread::invokeLater);

		stepOverlay.setCoreEngine(coreEngine);
		overlayManager.add(stepOverlay);
		overlayManager.add(lampOverlay);

		stepwisePanel = new StepwisePanel();
		stepwisePanel.setCoreEngine(coreEngine);
		iconProvider = new IconProvider(skillIconManager, itemManager, spriteManager);
		iconProvider.setOnImageLoaded(stepwisePanel::repaint);
		stepwisePanel.setIconProvider(iconProvider);
		stepwisePanel.setGuide(guide);
		stepwisePanel.setPersistence(persistence);
		stepwisePanel.setPluginVersion(VersionInfo.getVersion());
		stepwisePanel.showInitialState();

		navButton = NavigationButton.builder()
			.tooltip("Stepwise")
			.icon(ImageUtil.loadImageResource(StepwisePlugin.class, "panel_icon.png"))
			.priority(5)
			.panel(stepwisePanel)
			.build();
		clientToolbar.addNavigation(navButton);

		// Arm the login gate here when the plugin starts while already logged in.
		// GameStateChanged only fires on a transition, so waiting for LOGGED_IN would leave
		// every handler gated behind loginReady forever.
		clientThread.invokeLater(() ->
		{
			// The session fields belong to the client thread. Invokes run in order, so this
			// follows the last shutDown's save.
			resetSession();
			if (LastSessionGate.isBeforeLogin(client.getGameState()))
			{
				showLastSession();
				return;
			}
			if (client.getGameState() != GameState.LOGGED_IN
				|| !tracksProgressOn(client.getWorldType()))
			{
				return;
			}
			armLoginSnapshot();
			initializeBaselines();
			log.debug("Started while already logged in — arming login snapshot");
		});
	}

	private void armLoginSnapshot()
	{
		loginSnapshotPending = true;
		loginTickCounter = 0;
	}

	/**
	 * Takes the quest and XP baselines the handlers diff against, and teaches
	 * {@link #itemIdsByName} the seed names. Client thread: item compositions need it.
	 */
	private void initializeBaselines()
	{
		initializeQuestStates();
		initializeXpTracking();
		learnSeedNames();
	}

	@Override
	protected void shutDown()
	{
		started = false;
		// Before the static setters below: a build that has not begun never resolves against
		// the nulls they leave. One already running can still fail, and logs it quietly.
		if (lastSessionBuild != null)
		{
			lastSessionBuild.cancel(false);
			lastSessionBuild = null;
		}
		overlayManager.remove(stepOverlay);
		stepOverlay.setCoreEngine(null);
		overlayManager.remove(lampOverlay);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (stepwisePanel != null)
		{
			stepwisePanel.dispose();
			stepwisePanel = null;
		}

		// shutDown runs on the Swing thread while the client thread keeps ticking, so the final
		// save goes to the client thread rather than serialising state a tick is changing.
		// Nothing the save reads is cleared below: it holds the engine, which holds its guide.
		// RuneLite keeps the plugin instance across disable and enable, so this also resets it.
		CoreEngine engine = coreEngine;
		clientThread.invoke(() ->
		{
			endSession(engine);
			lastSessionGate.reset();
		});
		coreEngine = null;
		SnapshotBuilder.setQuestStageData(null);
		SnapshotBuilder.setGuideBudget(null);
		ResolutionEngine.initItemClasses(null);
		guide = null;
		requiredVarIds = null;
		milestonesFile = null;
		lampPolicy = LampPolicy.EMPTY;
	}

	/**
	 * Saves the session being left, then forgets it. Client thread: the save serialises state
	 * the client thread changes, and the session fields are its own.
	 *
	 * @param engine the engine holding the session, or null when the guide failed to load
	 */
	private void endSession(CoreEngine engine)
	{
		saveIfLoaded(engine);
		resetSession();
	}

	/**
	 * Saves the session's progress, but only once its account has loaded: before the login
	 * snapshot runs, PlayerState was never loaded, and saving it would write empty step sets
	 * over the account's real progress. Client thread.
	 */
	private void saveIfLoaded(CoreEngine engine)
	{
		if (engine != null && loginReady)
		{
			engine.forceSave();
		}
	}

	/**
	 * Saves progress when the client closes, which never calls shutDown. Without this the
	 * progress held back by the save debounce is lost.
	 *
	 * <p>Posted on the Swing thread while the client thread keeps running, so the save runs
	 * on the client thread and the client waits for it before exiting. RuneLite writes config
	 * to disk while this event is posted, before the save can run, so the save is followed by
	 * a second write. That write also covers a logout save made since RuneLite's.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		CompletableFuture<Void> saved = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				saveIfLoaded(coreEngine);
				configManager.sendConfig();
			}
			finally
			{
				saved.complete(null);
			}
		});
		event.waitFor(saved);
	}

	/**
	 * Close the login gate and forget everything held for the session being left. The
	 * change-detection baselines describe the account that was playing, so one kept past a
	 * logout would drop the next account's first change when it matched: a bank with the same
	 * contents, or a quest reaching the stage the last account left it on. Also forgets how
	 * many loads failed, so the next login tries afresh.
	 */
	private void resetSession()
	{
		loginReady = false;
		loginSnapshotPending = false;
		loginQuestProgressLogged = false;
		// A held line belongs to the account that is leaving.
		pendingTearsOfGuthixLine = null;
		depositBoxOpen = false;
		depositBoxInventorySnapshot = null;
		// An open listing would read the next "2 x Thing." chat line as a seed in the box.
		seedBoxListing = null;
		removeAllBirdhouseInfoBoxes();
		pendingProbeSpace = null;
		lastBirdhouseDialog = null;
		lastTearsJournalText = null;
		Arrays.fill(previousXp, -1);
		containerContents.clear();
		previousQuestStates.clear();
		lastQuestVarValues.clear();
		// PlayerState.regionId is transient, so the next session starts with no region. A
		// tracker still holding the old region would report "no change" and never fill it in
		// until the player crosses a boundary.
		regionTracker.reset();
		// Empty, so the next session's first bird house rows refresh the sidebar.
		lastBirdhouseSignature = "";
		loginLoadFailures = 0;
		// A logout closes the chooser without a WidgetClosed, and the remembered item op
		// belongs to the account leaving.
		lampChooser.reset();
		lampOverlay.setRecommendation(null);
	}

	@Provides
	StepwiseConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(StepwiseConfig.class);
	}

	// ---------------------------------------------------------------
	// STAT_CHANGED
	// ---------------------------------------------------------------
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (coreEngine == null || !loginReady)
		{
			return;
		}

		Skill skill = event.getSkill();
		int ordinal = skill.ordinal();
		int currentXp = event.getXp();
		int xpGained = 0;

		if (previousXp[ordinal] >= 0)
		{
			xpGained = currentXp - previousXp[ordinal];
		}
		previousXp[ordinal] = currentXp;

		if (xpGained <= 0)
		{
			return;
		}

		coreEngine.onStatChanged(skill.getName(), currentXp, event.getLevel());
	}

	// ---------------------------------------------------------------
	// GAME_STATE_CHANGED (login gate + quest init)
	// ---------------------------------------------------------------
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (coreEngine == null)
		{
			return;
		}

		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// LOGGED_IN is not a login. The client re-enters it after every loading screen
			// (teleports, boats, stairs). Re-running the snapshot reloads PlayerState from
			// disk and wipes every transient field, such as the seed box read stamp and run
			// energy.
			//
			// A real login arrives with no session established and none pending. The
			// LOGIN_SCREEN/HOPPING arm below clears both flags, so logouts, hops and fresh
			// starts still count.
			//
			// The pending check matters too: the map load raises LOGGED_IN again in the
			// tick between arming and the snapshot. Re-arming then resets loginTickCounter,
			// which can starve the max-ticks fallback, and re-baselines XP, which swallows
			// the XP gained in between.
			if (loginReady || loginSnapshotPending)
			{
				return;
			}
			// The saved state failed to load on every attempt; stay paused until a logout or
			// hop rather than retrying at every loading screen.
			if (loginLoadFailures >= LOGIN_LOAD_ATTEMPTS)
			{
				return;
			}
			// Left unarmed, the session stays behind loginReady: no handler reaches the
			// engine and nothing is saved. The next hop clears what was held meanwhile.
			if (!tracksProgressOn(client.getWorldType()))
			{
				// Debug: LOGGED_IN re-fires on every loading screen while the gate stays shut.
				log.debug("PvP Arena world: guide tracking paused until you leave");
				// The panel keeps any last session it shows: the live engine still holds the
				// previous account, which would be worse to show.
				return;
			}
			armLoginSnapshot();
			clientThread.invokeLater(this::initializeBaselines);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			// The engine outlives a logout or hop, so anything still held would be credited
			// to the next account.
			endSession(coreEngine);
			// Defensive: whatever the client reports on the way out, the next session must
			// not start with the bank marked open.
			coreEngine.onBankOpenChanged(false);
			// A carried slayer baseline would read the next account's first sample as a
			// task completing.
			coreEngine.onSlayerTrackingReset();
			// Objects and conversations seen this session, held beside the player state
			// because they are observed before it loads.
			coreEngine.forgetSessionSightings();
			// Last, after the resets above, which could otherwise move the snapshot version
			// the panel waits past.
			if (lastSessionGate.showsLastSessionOn(event.getGameState()))
			{
				showLastSession();
			}
		}
	}

	/**
	 * Puts the welcome page up in place of the account that left, then builds the last
	 * account's progress for it from saved state. Client thread.
	 *
	 * <p>The build resolves the whole guide over the account's saved state, which takes a few
	 * hundred milliseconds cold, so it runs on the background executor. The engine it builds
	 * shares nothing mutable with the live one and goes to the panel when done.
	 */
	private void showLastSession()
	{
		StepwisePanel panel = stepwisePanel;
		if (panel == null)
		{
			return;
		}
		lastSessionGate.lastSessionShown();
		int generation = panel.showLoggedOut();
		StepwisePersistence.SessionSummary summary = persistence.loadSessionSummary();
		if (summary == null)
		{
			return;
		}
		String savedKey = summary.getRsProfileKey();
		String playerName = summary.getPlayerName();
		StepwiseGuide sessionGuide = guide;
		MilestonesFile sessionMilestones = milestonesFile;
		if (lastSessionBuild != null)
		{
			lastSessionBuild.cancel(false);
		}
		lastSessionBuild = executor.submit(() ->
		{
			try
			{
				// A summary with no profile key gets its profile looked up by the player's name.
				String profileKey = savedKey != null ? savedKey : LastSession.profileNamed(playerName,
					configManager.getRSProfiles(),
					key -> new StepwisePersistence(ConfigStore.readOnly(configManager, key), gson)
						.hasSavedPlayerState());
				if (profileKey == null)
				{
					return;
				}
				ConfigStore store = ConfigStore.readOnly(configManager, profileKey);
				CoreEngine session = LastSession.build(sessionGuide, sessionMilestones, store, gson);
				if (session != null)
				{
					panel.showLastSession(generation, session);
				}
			}
			catch (RuntimeException e)
			{
				// A shutdown mid-build pulls the data the resolve reads; that is no fault.
				if (started)
				{
					log.warn("Failed to load the last session for the welcome page", e);
				}
				else
				{
					log.debug("Last session build stopped by shutdown", e);
				}
			}
		});
	}

	/** The baseline this class diffs against to spot a quest changing state. */
	private void initializeQuestStates()
	{
		previousQuestStates.clear();
		// The first tick after login polls, catching anything that moved before it.
		questStatesDirty = true;
		for (Quest quest : Quest.values())
		{
			try
			{
				QuestState state = quest.getState(client);
				previousQuestStates.put(quest, state);
			}
			catch (Exception e)
			{
				// Some quests may not be queryable
			}
		}
	}

	/** Every quest the client can answer for, by name, in the guide's terms. */
	private Map<String, QuestStatus> readQuestStatuses()
	{
		Map<String, QuestStatus> statuses = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			try
			{
				QuestState state = quest.getState(client);
				statuses.put(quest.getName(), state == QuestState.FINISHED ? QuestStatus.COMPLETED
					: state == QuestState.IN_PROGRESS ? QuestStatus.IN_PROGRESS : QuestStatus.NOT_STARTED);
			}
			catch (Exception e)
			{
				// Some quests may not be queryable
			}
		}
		return statuses;
	}

	@SuppressWarnings("deprecation") // Skill.OVERALL — no gameval replacement
	private void initializeXpTracking()
	{
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			previousXp[skill.ordinal()] = client.getSkillExperience(skill);
		}
	}

	// ---------------------------------------------------------------
	// BIRD HOUSES, TEARS OF GUTHIX (per-tick state)
	// ---------------------------------------------------------------
	// Bird houses. The four transmit varps (1626-1629) are only reliable within
	// Fossil Island, so they are polled per tick, not on VarbitChanged. No varp says
	// "ready": completion is wall-clock, seededAt + 50 min. A "Seed level is: X/10"
	// check refines the timer.

	/** Regions covering the four Fossil Island bird house spaces. */
	private static final Set<Integer> FOSSIL_ISLAND_REGIONS =
		new HashSet<>(Arrays.asList(14650, 14651, 14652, 14906, 14907, 15162, 15163));

	/**
	 * Tile of each bird house space, binding a "Seeds" check to a house by the clicked
	 * object's position. Tiles are the wiki's: Mushroom Meadow's pair is in the north-west,
	 * one to the north and one by the Tar Swamp, and Verdant Valley's pair is south of the
	 * Museum Camp. See https://oldschool.runescape.wiki/w/Bird_house_trapping
	 */
	private static final Map<BirdhouseTracker.BirdhouseSpace, WorldPoint> BIRDHOUSE_TILES =
		birdhouseTiles();

	private static final Pattern SEED_LEVEL_PATTERN =
		Pattern.compile("Seed level is:\\s*(\\d+)\\s*/\\s*10");

	/** Single collapsed InfoBox for all four bird house spaces (null while none seeded). */
	private BirdhouseInfoBox birdhouseInfoBox;

	/** Space the player last clicked, so the next "Seed level" dialog binds to it. */
	private BirdhouseTracker.BirdhouseSpace pendingProbeSpace;
	/** Last seed-level dialog text applied, to avoid re-applying while it stays open. */
	private String lastBirdhouseDialog;
	/** Last rendered sidebar signature; the panel refreshes only when it changes. */
	private String lastBirdhouseSignature = "";

	/** Last Tears of Guthix journal page read, so one left open is only read once. */
	private String lastTearsJournalText;

	/** Deepest the journal page is walked for text. Its lines sit one layer down. */
	private static final int JOURNAL_TEXT_DEPTH = 4;

	private StepwisePersistence persistence;

	private static Map<BirdhouseTracker.BirdhouseSpace, WorldPoint> birdhouseTiles()
	{
		Map<BirdhouseTracker.BirdhouseSpace, WorldPoint> m =
			new EnumMap<>(BirdhouseTracker.BirdhouseSpace.class);
		m.put(BirdhouseTracker.BirdhouseSpace.MEADOW_NORTH, new WorldPoint(3677, 3882, 0));
		m.put(BirdhouseTracker.BirdhouseSpace.MEADOW_SOUTH, new WorldPoint(3678, 3815, 0));
		m.put(BirdhouseTracker.BirdhouseSpace.VALLEY_NORTH, new WorldPoint(3768, 3761, 0));
		m.put(BirdhouseTracker.BirdhouseSpace.VALLEY_SOUTH, new WorldPoint(3763, 3755, 0));
		return m;
	}

	/**
	 * Per-tick bird house handling: sample the four varps (applied only while on
	 * Fossil Island), apply any seed-level check to refine the timer, reconcile the
	 * in-game InfoBoxes, and refresh the sidebar rows when the display changes.
	 */
	private void handleBirdhouses()
	{
		Player local = client.getLocalPlayer();
		WorldPoint loc = local == null ? null : local.getWorldLocation();
		boolean inRegion = loc != null && loc.getPlane() == 0
			&& FOSSIL_ISLAND_REGIONS.contains(loc.getRegionID());

		Map<BirdhouseTracker.BirdhouseSpace, Integer> varps =
			new EnumMap<>(BirdhouseTracker.BirdhouseSpace.class);
		for (BirdhouseTracker.BirdhouseSpace space : BirdhouseTracker.BirdhouseSpace.values())
		{
			varps.put(space, client.getVarpValue(space.getVarpId()));
		}

		long now = Instant.now().getEpochSecond();
		coreEngine.updateBirdhouses(varps, inRegion, now);
		applyBirdhouseProbe(now);
		reconcileBirdhouseInfoBoxes();

		// The snapshot version only moves on guide events, so a house being seeded or a
		// minute ticking down would not refresh the sidebar alone. Nudge it when the
		// displayed rows change (about once a minute).
		String signature = birdhouseDisplaySignature(now);
		if (!signature.equals(lastBirdhouseSignature))
		{
			lastBirdhouseSignature = signature;
			coreEngine.markTimersDirty();
		}
	}

	/** Compact fingerprint of the sidebar's bird house rows (space + minutes/ready). */
	private String birdhouseDisplaySignature(long now)
	{
		BirdhouseTracker tracker = coreEngine.getBirdhouseTracker();
		StringBuilder sb = new StringBuilder();
		for (BirdhouseTracker.BirdhouseSpace space : BirdhouseTracker.BirdhouseSpace.values())
		{
			if (!tracker.isSeeded(space))
			{
				continue;
			}
			long remaining = tracker.remainingSeconds(space, now);
			sb.append(space.ordinal()).append(':').append((remaining + 59) / 60).append(';');
		}
		return sb.toString();
	}

	/** Read the "Seed level is: X/10" dialog and refine the bound house's timer once. */
	private void applyBirdhouseProbe(long now)
	{
		String text = birdhouseDialogText(client.getWidget(InterfaceID.Messagebox.TEXT));
		if (text == null)
		{
			text = birdhouseDialogText(client.getWidget(InterfaceID.Objectbox.TEXT));
		}
		if (text == null)
		{
			lastBirdhouseDialog = null;
			return;
		}
		if (text.equals(lastBirdhouseDialog))
		{
			return;
		}
		lastBirdhouseDialog = text;

		Matcher m = SEED_LEVEL_PATTERN.matcher(text);
		if (pendingProbeSpace == null || !m.find())
		{
			return;
		}
		coreEngine.applyBirdhouseSeedProbe(pendingProbeSpace, Integer.parseInt(m.group(1)), now);
		pendingProbeSpace = null;
	}

	/**
	 * Read the weekly wait off the Tears of Guthix Quest Journal page while it is open. The
	 * client exposes the wait nowhere else.
	 *
	 * <p>Polled per tick, not on WidgetLoaded: the journal group loads once and is then
	 * repainted in place as the player clicks through the quest list.
	 */
	private void handleTearsOfGuthixJournal()
	{
		Widget title = client.getWidget(InterfaceID.Questjournal.TITLE);
		if (title == null || title.isHidden()
			|| !TearsOfGuthixTracker.QUEST_NAME.equalsIgnoreCase(plainWidgetText(title)))
		{
			lastTearsJournalText = null;
			return;
		}

		Widget layer = client.getWidget(InterfaceID.Questjournal.TEXTLAYER);
		if (layer == null)
		{
			lastTearsJournalText = null;
			return;
		}

		StringBuilder sb = new StringBuilder();
		collectWidgetText(layer, sb, JOURNAL_TEXT_DEPTH);
		String text = sb.toString().trim();
		if (text.isEmpty() || text.equals(lastTearsJournalText))
		{
			return;
		}
		lastTearsJournalText = text;
		coreEngine.onTearsOfGuthixJournal(text);
	}

	/** One widget's own text with its tags stripped and trimmed; "" for none. */
	private static String plainWidgetText(Widget widget)
	{
		String text = widget == null ? null : widget.getText();
		return text == null ? "" : Text.removeTags(text).trim();
	}

	/** Tags stripped, skipping the regex for the common untagged line. */
	private static String plain(String message)
	{
		return message.indexOf('<') < 0 ? message : Text.removeTags(message);
	}

	/**
	 * Append every visible line of text under a widget, separated by spaces. All three child
	 * arrays are walked, since which one holds the journal lines is an interface detail.
	 *
	 * <p>Hidden nodes are skipped: the journal repaints in place, so lines from the previous
	 * page remain, and a stale "in 4 days" would win because the parse takes the first match.
	 * {@code isSelfHidden}, not {@code isHidden}, which walks every ancestor per node.
	 */
	private void collectWidgetText(Widget widget, StringBuilder sb, int depth)
	{
		if (widget == null || depth < 0 || widget.isSelfHidden())
		{
			return;
		}

		String text = widget.getText();
		if (text != null && !text.isEmpty())
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Text.removeTags(text));
		}

		collectWidgetText(widget.getStaticChildren(), sb, depth);
		collectWidgetText(widget.getDynamicChildren(), sb, depth);
		collectWidgetText(widget.getNestedChildren(), sb, depth);
	}

	/** One child array of the walk above, kept separate so no array is allocated per node. */
	private void collectWidgetText(Widget[] children, StringBuilder sb, int depth)
	{
		if (children == null)
		{
			return;
		}
		for (Widget child : children)
		{
			collectWidgetText(child, sb, depth - 1);
		}
	}

	/** Add/remove the single collapsed bird house InfoBox as houses seed and empty. */
	private void reconcileBirdhouseInfoBoxes()
	{
		BirdhouseTracker tracker = coreEngine.getBirdhouseTracker();
		// Checked every tick, so the setting applies within a tick of being changed.
		boolean shouldShow = config.showBirdhouseInfoBox() && tracker.anySeeded();

		if (shouldShow && birdhouseInfoBox == null)
		{
			BufferedImage image = itemManager.getImage(
				BirdhouseTracker.BirdhouseTier.NORMAL.getItemId());
			birdhouseInfoBox = new BirdhouseInfoBox(image, this, tracker);
			infoBoxManager.addInfoBox(birdhouseInfoBox);
		}
		else if (!shouldShow && birdhouseInfoBox != null)
		{
			infoBoxManager.removeInfoBox(birdhouseInfoBox);
			birdhouseInfoBox = null;
		}
	}

	private void removeAllBirdhouseInfoBoxes()
	{
		if (birdhouseInfoBox != null)
		{
			infoBoxManager.removeInfoBox(birdhouseInfoBox);
			birdhouseInfoBox = null;
		}
	}

	/** On a bird house click, remember which house so the next check dialog binds to it. */
	@SuppressWarnings("deprecation") // getPlane()/fromScene — object scene coords
	private void captureBirdhouseInteraction(MenuOptionClicked event)
	{
		// Only game-object clicks carry scene coords in param0/param1. An NPC or widget
		// action whose target says "bird" would bind to a garbage tile.
		if (!isGameObjectAction(event.getMenuAction()))
		{
			return;
		}
		String target = event.getMenuTarget();
		if (target == null || !Text.removeTags(target).toLowerCase(Locale.ROOT).contains("bird"))
		{
			return;
		}
		WorldPoint wp = WorldPoint.fromScene(client, event.getParam0(), event.getParam1(), client.getPlane());
		if (wp != null)
		{
			pendingProbeSpace = nearestBirdhouseSpace(wp);
			// Let this house's check dialog apply even if its text matches a sibling's
			// previous reading.
			lastBirdhouseDialog = null;
		}
	}

	private static boolean isGameObjectAction(MenuAction action)
	{
		return action == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION
			|| action == MenuAction.WIDGET_TARGET_ON_GAME_OBJECT;
	}

	/** Nearest of the four bird house tiles to a clicked object. */
	static BirdhouseTracker.BirdhouseSpace nearestBirdhouseSpace(WorldPoint wp)
	{
		BirdhouseTracker.BirdhouseSpace best = null;
		long bestDist = Long.MAX_VALUE;
		for (Map.Entry<BirdhouseTracker.BirdhouseSpace, WorldPoint> e : BIRDHOUSE_TILES.entrySet())
		{
			long dx = e.getValue().getX() - wp.getX();
			long dy = e.getValue().getY() - wp.getY();
			long d = dx * dx + dy * dy;
			if (d < bestDist)
			{
				bestDist = d;
				best = e.getKey();
			}
		}
		return best;
	}

	/** A dialog's plain text when it talks about bird houses, else null. */
	private static String birdhouseDialogText(Widget dialog)
	{
		String plain = plainWidgetText(dialog);
		String lower = plain.toLowerCase(Locale.ROOT);
		return lower.contains("seed level") || lower.contains("bird house") || lower.contains("birdhouse")
			? plain : null;
	}

	// ---------------------------------------------------------------
	// CHAT_MESSAGE (KC, collection log, diary, Tears of Guthix, seed box)
	// ---------------------------------------------------------------
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (coreEngine == null)
		{
			return;
		}

		if (!loginReady)
		{
			// Hold Juna's eligibility line rather than drop it; see pendingTearsOfGuthixLine.
			// The type filter matches the armed path below, so every held line is one the drain
			// acts on.
			if (event.getType() == ChatMessageType.GAMEMESSAGE)
			{
				String said = plain(event.getMessage());
				if (TearsOfGuthixTracker.isEligibilityMessage(said))
				{
					pendingTearsOfGuthixLine = said;
				}
			}
			return;
		}

		// Juna states the weekly wait and the adventuring gate in dialogue, which lets a
		// player correct a stale Tears of Guthix timer in-game.
		if (event.getType() == ChatMessageType.DIALOG || event.getType() == ChatMessageType.MESBOX)
		{
			String said = plain(event.getMessage());
			coreEngine.onTearsOfGuthixDialogue(said);
			// A finished diary tier announces itself in a message box, not in game chat.
			EventParser.parseDiary(said)
				.ifPresent(e -> coreEngine.onDiaryCompleted(e.getArea(), e.getTier()));
			return;
		}

		if (!EventParser.isReadableGameChat(event.getType()))
		{
			return;
		}

		String message = plain(event.getMessage());

		if (event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			EventParser.parseKillCount(message)
				.ifPresent(e -> coreEngine.onKillCount(e.getTarget(), e.getCount()));
			EventParser.parseCollectionLog(message)
				.ifPresent(e -> coreEngine.onCollectionLog(e.getItemName()));
			// Juna's daily eligibility line, for players with her reminder on. It outranks
			// Stepwise's own visit clock.
			coreEngine.onTearsOfGuthixMessage(message);
		}

		// Checked before the movement lines. A whole-box statement is the only correction
		// for a drifted tally, since movement lines only adjust the count.
		if (EventParser.isSeedBoxReportedEmpty(message))
		{
			seedBoxListing = null;
			coreEngine.onSeedBoxChanged(List.of());
			return;
		}
		if (EventParser.isSeedBoxContentsHeader(message))
		{
			seedBoxListing = new SeedBoxListing();
			return;
		}
		if (seedBoxListing != null
			&& SeedBoxListing.read(message, seedBoxListing, itemIdsByName))
		{
			return;
		}
		// Anything else ends the listing. The entry pattern matches common chat shapes, so
		// an open listing would swallow the next unrelated "2 x Thing.".
		commitSeedBoxListing();

		EventParser.parseSeedBoxMove(message).ifPresent(this::applySeedBoxMove);
	}

	/**
	 * Hands a finished Check listing to the engine, if one was being read.
	 *
	 * <p>Committed once, at the end, not per line: each commit stamps
	 * {@code PlayerState.seedBoxVerifiedAt}, so a per-line commit cut short would mark a
	 * half-read box as known. The whole listing arrives within one tick, so the tick ends it.
	 *
	 * <p>Entries that could not be named are dropped and the rest committed. Short is the
	 * safe direction: a missing seed asks the player to withdraw it, while an invented one is
	 * a planting they cannot make. See {@link #learnSeedNames}.
	 */
	private void commitSeedBoxListing()
	{
		if (seedBoxListing == null)
		{
			return;
		}
		List<ItemStack> read = seedBoxListing.getSeeds();
		int lines = seedBoxListing.getLines();
		seedBoxListing = null;
		if (lines == 0)
		{
			// The game prints the header only when there is something to list, so this is
			// a lost listing, not an empty box. An empty box has its own message.
			return;
		}
		if (read.size() < lines)
		{
			log.debug("Seed box listed {} lines and {} were names we know", lines, read.size());
		}
		coreEngine.onSeedBoxChanged(read);
	}

	/**
	 * Teaches {@link #itemIdsByName} every farming seed's name. A seed box holds the seeds
	 * that are not in the inventory, so without this a Check made before opening the bank
	 * would resolve nothing and commit the box as empty.
	 *
	 * <p>Names come from item compositions because the listing prints the game's own
	 * wording. Requires the client thread. {@code putIfAbsent}, so ids reported by a real
	 * container win.
	 */
	private void learnSeedNames()
	{
		for (int seed : SeedBoxListing.SEED_ITEM_IDS)
		{
			ItemComposition comp = itemManager.getItemComposition(seed);
			if (comp != null && comp.getName() != null && !comp.getName().isEmpty())
			{
				itemIdsByName.putIfAbsent(comp.getName().toLowerCase(Locale.ROOT), seed);
			}
		}
	}

	/**
	 * Folds a seed box movement into the tracked contents.
	 *
	 * <p>Seeds leaving are applied by name, resolved by the engine against the box. Seeds
	 * arriving need an item id from {@link #itemIdsByName}. An unknown name is dropped: an
	 * entry with a placeholder id satisfies no item check and only makes the box look
	 * occupied.
	 */
	private void applySeedBoxMove(EventParser.SeedBoxMove move)
	{
		if (move.getQuantity() < 0)
		{
			coreEngine.onSeedBoxTaken(move.getItemName(), -move.getQuantity());
			return;
		}

		Integer itemId = itemIdsByName.get(move.getItemName().toLowerCase(Locale.ROOT));
		if (itemId == null)
		{
			log.debug("Seed box stored an item never seen in a tracked container: {}",
				move.getItemName());
			return;
		}
		coreEngine.onSeedBoxStored(
			new ItemStack(itemId, move.getQuantity(), move.getItemName()));
	}

	// ---------------------------------------------------------------
	// GAME_TICK (login gate, quest polling, region tracking, save)
	// ---------------------------------------------------------------
	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick event)
	{
		if (coreEngine == null)
		{
			return;
		}

		// A Check listing arrives within one tick, so the tick ends it.
		commitSeedBoxListing();

		checkLampChooserStillOpen();
		checkBankStillOpen();

		// Sprites require client thread access
		if (iconProvider != null)
		{
			iconProvider.loadSprites();
		}

		// --- Login readiness gate ---
		if (loginSnapshotPending)
		{
			loginTickCounter++;
			Player localPlayer = client.getLocalPlayer();
			String name = localPlayer != null ? localPlayer.getName() : null;

			// Ready only once readAccount has loaded the account. It does not without a local
			// player, on a world that does not track progress, or when the saved state fails to
			// load, and opening the gate then would let the first save write empty progress over
			// the account's real progress. Staying shut is the safe failure: every handler and
			// every save stays gated behind loginReady.
			boolean named = name != null && !name.isEmpty();
			// Once, on the tick the wait runs out: a client with no local player stays here,
			// and a warning per tick would flood client.log.
			if (!named && loginTickCounter == LOGIN_READY_MAX_TICKS)
			{
				log.warn("Player name still unknown after {} ticks, reading the account without it",
					loginTickCounter);
			}
			if ((named || loginTickCounter >= LOGIN_READY_MAX_TICKS) && readAccount())
			{
				loginSnapshotPending = false;
				loginReady = true;
				log.debug("Login ready after {} tick(s)", loginTickCounter);
			}

			// Only once PlayerState exists. The tracker announces a region once per session,
			// and CoreEngine drops it while the state is null, leaving region conditions
			// blind until the player crosses a boundary.
			if (loginReady)
			{
				// Drained here for the same reason: the state it corrects has just loaded.
				if (pendingTearsOfGuthixLine != null)
				{
					String line = pendingTearsOfGuthixLine;
					pendingTearsOfGuthixLine = null;
					coreEngine.onTearsOfGuthixMessage(line);
				}
				handleRegionTracking();
				// Resolve the scanned state now, silently, so every game event after it can
				// announce the step it finishes.
				coreEngine.flushIfDirty();
				// The account has loaded, so the panel leaves the last session's page.
				if (stepwisePanel != null)
				{
					stepwisePanel.showLoggedIn();
					lastSessionGate.accountLoaded();
				}
			}

			return;
		}

		if (!loginReady)
		{
			return;
		}

		handleBirdhouses();

		handleTearsOfGuthixJournal();

		handleRegionTracking();

		// After CoreEngine.resetGuideProgress(), re-run the login flow to repopulate
		// PlayerState from live game data and trigger milestone auto-latch.
		if (coreEngine.isResetPending())
		{
			coreEngine.consumeResetPending();
			if (!readAccount())
			{
				// The save gate is shut and the login gate re-armed (or tracking paused);
				// the rest of this tick would save what did load.
				return;
			}
			// The re-scan replaces PlayerState, region included, so the tracker must
			// announce the region again.
			regionTracker.reset();
			// Resolve the scanned state now, silently, as the login gate does, so the game
			// events polled below can announce the step they finish.
			coreEngine.flushIfDirty();
			log.debug("Login re-scan completed after guide progress reset");
		}

		// Remaining count and streak are sampled together: var-change events deliver them
		// one at a time in no guaranteed order, and telling a finished task from a
		// cancelled one needs both from the same tick.
		handleSlayerTask();

		if (questStatesDirty && !previousQuestStates.isEmpty())
		{
			questStatesDirty = false;
			for (Quest quest : Quest.values())
			{
				try
				{
					QuestState currentState = quest.getState(client);
					QuestState previousState = previousQuestStates.get(quest);

					if (previousState != null && previousState != QuestState.FINISHED
						&& currentState == QuestState.FINISHED)
					{
						int totalQuestPoints = client.getVarpValue(VarPlayerID.QP);
						coreEngine.onQuestCompleted(quest.getName(), totalQuestPoints);
					}

					if (previousState == QuestState.NOT_STARTED
						&& currentState == QuestState.IN_PROGRESS)
					{
						coreEngine.onQuestProgress(quest.getName(), "IN_PROGRESS", null);
					}

					previousQuestStates.put(quest, currentState);
				}
				catch (Exception e)
				{
					// Some quests may not be queryable
				}
			}
		}

		// Batched resolution: once per tick, not per event.
		coreEngine.flushIfDirty();

		// Taken every tick, so turning the notification on later does not replay an old step.
		for (GuideStep done : coreEngine.takeAutoCompletedSteps())
		{
			notifier.notify(config.notifyStepComplete(),
				"Step done: " + TextMarkup.plainInstruction(done.getInstruction()));
		}

		coreEngine.tickSave();

		if (stepwisePanel != null)
		{
			stepwisePanel.refresh();
		}
	}

	// ---------------------------------------------------------------
	// VARBIT_CHANGED (quest progress, boats)
	// ---------------------------------------------------------------
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		questStatesDirty = true;
		if (coreEngine == null || !loginReady)
		{
			return;
		}

		int varbitId = event.getVarbitId();

		if (varbitId != -1)
		{
			int value = client.getVarbitValue(varbitId);

			String questName = QuestVarbits.getQuestForVarbit(varbitId);
			if (questName != null)
			{
				handleQuestVarbitProgress(questName, "varbit", varbitId, value);
			}

			// A lost boat takes its hold with it. Forget the hold's contents too, or an
			// identically stocked replacement boat would never be recorded.
			int lostBoatSlot = BoatVarbits.slotOfOwnedVarbit(varbitId);
			if (lostBoatSlot >= 0 && value == 0)
			{
				containerContents.remove(CargoHolds.containerOf(lostBoatSlot));
			}

			coreEngine.onVarbitValueChanged(varbitId, value);

			// Re-read the pouch so pouched runes count toward rune hasItem checks.
			if (RunePouch.isContentVarbit(varbitId))
			{
				updateRunePouch();
			}
		}

		int varpId = event.getVarpId();
		int varpValue = client.getVarpValue(varpId);

		String questName = QuestVarbits.getQuestForVarplayer(varpId);
		if (questName != null)
		{
			handleQuestVarbitProgress(questName, "varp", varpId, varpValue);
		}

		// Every varp reaches the engine here, bird house varps included; the bird house timers
		// read theirs separately, polled in handleBirdhouses(). The engine deduplicates
		// unchanged values.
		coreEngine.onVarpValueChanged(varpId, varpValue);
	}

	private void handleQuestVarbitProgress(String questName, String varType, int varId, int newValue)
	{
		Integer previousValue = lastQuestVarValues.get(questName);
		if (previousValue != null && previousValue == newValue)
		{
			return;
		}
		lastQuestVarValues.put(questName, newValue);

		// Logs the numeric quest stage that guide latches compare against, which the quest
		// journal never shows.
		log.debug("[quest-progress] {} ({} {}): {} -> {}",
			questName, varType, varId, previousValue != null ? previousValue : "?", newValue);

		coreEngine.onQuestProgress(questName, questStateName(findQuestByName(questName)), newValue);
	}

	private Quest findQuestByName(String name)
	{
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equals(name))
			{
				return quest;
			}
		}
		return null;
	}

	/** The quest's state by its {@link QuestState} name, or "UNKNOWN" when it cannot be read. */
	private String questStateName(Quest quest)
	{
		try
		{
			return quest != null ? quest.getState(client).name() : "UNKNOWN";
		}
		catch (Exception e)
		{
			return "UNKNOWN";
		}
	}

	/**
	 * Read a fixed set of vars into the player state, returning whether any changed.
	 *
	 * <p>An id the running client does not know is skipped.
	 *
	 * @param into  the player state's varbit or varp map
	 * @param read  the client's matching getter
	 */
	private static boolean scanVars(Map<Integer, Integer> into, Collection<Integer> ids, IntUnaryOperator read)
	{
		boolean changed = false;
		for (int id : ids)
		{
			try
			{
				int value = read.applyAsInt(id);
				Integer previous = into.put(id, value);
				changed |= previous == null || previous != value;
			}
			catch (IndexOutOfBoundsException e)
			{
				log.debug("Var {} does not exist in this client, skipping", id);
			}
		}
		return changed;
	}

	/**
	 * Read each quest var's current value into {@code questProgress}, for quests progressed
	 * before this session; live var changes are its only other source. Returns whether any
	 * nonzero value was read.
	 *
	 * <p>Also logs each in-progress quest's value once per session, since VarbitChanged only
	 * logs on change.
	 */
	private boolean scanQuestProgress(PlayerState ps, Map<Integer, String> questVars, String varType,
		IntUnaryOperator read)
	{
		boolean changed = false;
		for (Map.Entry<Integer, String> entry : questVars.entrySet())
		{
			try
			{
				int value = read.applyAsInt(entry.getKey());
				if (value == 0)
				{
					continue;
				}
				ps.getQuestProgress().put(entry.getValue(), value);
				changed = true;
				// Quest.getState runs a client script, so skip it unless DEBUG is on.
				if (!loginQuestProgressLogged && log.isDebugEnabled()
					&& "IN_PROGRESS".equals(questStateName(findQuestByName(entry.getValue()))))
				{
					log.debug("[quest-progress] (login) {} ({} {}): {}",
						entry.getValue(), varType, entry.getKey(), value);
				}
			}
			catch (IndexOutOfBoundsException e)
			{
				log.debug("Quest {} {} ({}) does not exist, skipping", varType, entry.getKey(),
					entry.getValue());
			}
		}
		return changed;
	}

	// ---------------------------------------------------------------
	// SCENE OBJECTS
	//
	// The game keeps no var for player-owned-house furniture, so a built object is only
	// observable while it stands in the loaded scene. CoreEngine records each sighting
	// permanently.
	//
	// All four loc classes are watched because house furniture spans them (beds as game
	// objects, wall-mounted furniture as decorative, floor decoration as ground, doorways as
	// wall objects), and the game cache fixes the class of each piece.
	//
	// Deliberately NOT gated on loginReady. The scene loads before the profile, so a player
	// who logs in inside their house would otherwise see no spawn. CoreEngine holds
	// sightings until the profile arrives.
	// ---------------------------------------------------------------
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		objectSpawned(event.getGameObject().getId());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		objectSpawned(event.getDecorativeObject().getId());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		objectSpawned(event.getGroundObject().getId());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		objectSpawned(event.getWallObject().getId());
	}

	private void objectSpawned(int objectId)
	{
		CoreEngine engine = coreEngine;
		if (engine != null)
		{
			engine.onObjectSpawned(objectId);
		}
	}

	// ---------------------------------------------------------------
	// ACTOR_DEATH (NPC kill detection)
	// ---------------------------------------------------------------
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (coreEngine == null || !loginReady)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		// Attribute an NPC death to the player if either was targeting the other
		if (event.getActor() instanceof NPC)
		{
			NPC npc = (NPC) event.getActor();
			boolean playerTargetingNpc = localPlayer.getInteracting() == npc;
			boolean npcTargetingPlayer = npc.getInteracting() == localPlayer;

			if (playerTargetingNpc || npcTargetingPlayer)
			{
				String name = npc.getName();
				if (name != null && !name.isEmpty())
				{
					coreEngine.onNpcKill(name);
				}
			}
		}
	}

	// ---------------------------------------------------------------
	// Item containers: inventory, worn items, bank, tackle box, seed vault, cargo holds
	// ---------------------------------------------------------------
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (coreEngine == null || !loginReady)
		{
			return;
		}

		int containerId = event.getContainerId();
		int cargoSlot = CargoHolds.slotOf(containerId);
		if (cargoSlot < 0 && !TRACKED_CONTAINERS.contains(containerId))
		{
			return;
		}
		Item[] items = event.getItemContainer().getItems();
		// Worn quantities are left out, so firing ammo does not re-read the equipment.
		int[] contents = new int[items.length * 2];
		for (int i = 0; i < items.length; i++)
		{
			contents[2 * i] = items[i].getId();
			contents[2 * i + 1] = containerId == InventoryID.WORN ? 0 : items[i].getQuantity();
		}
		if (Arrays.equals(containerContents.put(containerId, contents), contents))
		{
			return;
		}

		switch (containerId)
		{
			case InventoryID.INV:
				List<ItemStack> inventory = toItemStacks(items);
				// Before updating inventory state, so the bank updates in the same tick.
				if (depositBoxOpen)
				{
					inferDeposits(inventory);
				}
				coreEngine.onInventoryChanged(inventory);
				break;
			case InventoryID.WORN:
				coreEngine.onEquipmentChanged(readEquipment(items));
				break;
			case InventoryID.BANK:
				coreEngine.onBankChanged(toItemStacks(items));
				break;
			case InventoryID.TACKLE_BOX:
				coreEngine.onTackleBoxChanged(toItemStacks(items));
				break;
			case InventoryID.SEED_VAULT:
				coreEngine.onSeedVaultChanged(toItemStacks(items));
				break;
			default:
				coreEngine.onCargoHoldChanged(cargoSlot, toItemStacks(items));
		}
	}

	/** The worn items by slot, each named from its item composition. */
	private Map<EquipmentSlot, ItemStack> readEquipment(Item[] items)
	{
		Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
		for (EquipmentSlot slot : EquipmentSlot.values())
		{
			int slotIdx = EquipmentInventorySlot.valueOf(slot.name()).getSlotIdx();
			if (slotIdx < items.length && items[slotIdx].getId() != -1)
			{
				ItemComposition comp = itemManager.getItemComposition(items[slotIdx].getId());
				equipment.put(slot, new ItemStack(items[slotIdx].getId(), 1, comp.getName()));
			}
		}
		return equipment;
	}

	/**
	 * Records an item's name against its id, so a later chat line naming it resolves to an
	 * id. See {@link #itemIdsByName}.
	 *
	 * <p>Placeholders are skipped. A bank placeholder reports the real item's name with its
	 * own id, which would overwrite a good entry with an id no {@code hasItem} check matches.
	 *
	 * @param known the session's name-to-id map
	 */
	static void rememberItemId(Map<String, Integer> known, CanonicalItem item)
	{
		if (item.placeholder || item.name == null || item.name.isEmpty())
		{
			return;
		}
		known.put(item.name.toLowerCase(Locale.ROOT), item.itemId);
	}

	/**
	 * Occupied slots as engine item stacks, with noted items resolved to their unnoted
	 * form. Bank placeholders pass through as themselves with quantity 0, which counts
	 * as nothing and so needs no special handling.
	 */
	private List<ItemStack> toItemStacks(Item[] items)
	{
		List<ItemStack> itemStacks = new ArrayList<>();
		for (Item slot : items)
		{
			if (slot.getId() != -1)
			{
				CanonicalItem item = resolveCanonicalItem(slot.getId());
				itemStacks.add(new ItemStack(item.itemId, slot.getQuantity(), item.name));
				rememberItemId(itemIdsByName, item);
			}
		}
		return itemStacks;
	}

	// ---------------------------------------------------------------
	// WIDGETS (XP lamp chooser, bank, deposit box)
	// ---------------------------------------------------------------

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.XPREWARD)
		{
			openLampChooser();
			return;
		}
		if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			depositBoxOpen = true;
			ItemContainer inventory = client.getItemContainer(InventoryID.INV);
			depositBoxInventorySnapshot = inventory != null
				? quantitiesByName(toItemStacks(inventory.getItems())) : new HashMap<>();
			log.debug("Deposit box opened, inventory snapshot taken ({} item types)",
				depositBoxInventorySnapshot.size());
			return;
		}
		if (event.getGroupId() == InterfaceID.BANKMAIN && coreEngine != null)
		{
			coreEngine.onBankOpenChanged(true);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.XPREWARD)
		{
			// A close without an unload (a fixed/resizable layout switch moving the chooser)
			// keeps the session; only an unload ends it.
			if (lampChooser.onChooserClosed(event.isUnload()))
			{
				lampOverlay.setRecommendation(null);
			}
			return;
		}

		// As with the chooser, a close without an unload is the interface moving to another
		// layout, not the player shutting it.
		if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			if (event.isUnload())
			{
				depositBoxOpen = false;
				depositBoxInventorySnapshot = null;
				log.debug("Deposit box closed");
			}
			return;
		}

		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload() && coreEngine != null)
		{
			coreEngine.onBankOpenChanged(false);
		}
	}

	// ---------------------------------------------------------------
	// XP LAMP CHOOSER
	// ---------------------------------------------------------------

	/**
	 * Resolve the guide's skill for the chooser that just opened, once for the session; the
	 * overlay draws it each frame.
	 */
	private void openLampChooser()
	{
		int itemId = lampChooser.onChooserOpened(client.getTickCount());
		LampRecommendation rec = recommendLamp(itemId);
		lampOverlay.setRecommendation(rec);
		log.debug("XP chooser opened from item {}: recommends {}", itemId, rec);
	}

	private LampRecommendation recommendLamp(int itemId)
	{
		if (coreEngine == null || !loginReady || lampPolicy.isEmpty())
		{
			return null;
		}
		PlayerState state = coreEngine.getPlayerState();
		if (state == null)
		{
			return null;
		}
		// Without a snapshot the active step is unknown, and the finished-guide fallback would
		// place the player at a step latched far ahead of the route.
		DashboardSnapshot snapshot = coreEngine.getLastSnapshot();
		if (snapshot == null)
		{
			return null;
		}
		String activeStepId = snapshot.getGuidePosition() != null
			? snapshot.getGuidePosition().getStepId() : null;
		return LampAdvisor.recommend(lampPolicy, guide.stepPositionIndex(), activeStepId,
			coreEngine.getCompletedSteps(), state::getSkillLevel, itemId);
	}

	/**
	 * End a chooser session whose interface is gone without a WidgetClosed having said so, so
	 * a stale highlight can never outlive the chooser.
	 */
	private void checkLampChooserStillOpen()
	{
		if (lampChooser.isOpen()
			&& lampChooser.onGameTick(client.getWidget(InterfaceID.Xpreward.UNIVERSE) != null))
		{
			lampOverlay.setRecommendation(null);
		}
	}

	/**
	 * Mark the bank shut when its interface is gone without a WidgetClosed having said so, so
	 * a carry-check reminder never outlives the bank.
	 */
	private void checkBankStillOpen()
	{
		PlayerState state = coreEngine.getPlayerState();
		if (state != null && state.isBankOpen()
			&& client.getWidget(InterfaceID.Bankmain.UNIVERSE) == null)
		{
			coreEngine.onBankOpenChanged(false);
		}
	}

	// ---------------------------------------------------------------
	// DEPOSIT BOX INFERENCE
	// ---------------------------------------------------------------

	/**
	 * Quantities by canonical name, for deposit inference. Noted items are already resolved
	 * to their un-noted name, so bank merges work correctly.
	 */
	private static Map<String, Integer> quantitiesByName(List<ItemStack> stacks)
	{
		Map<String, Integer> quantities = new HashMap<>();
		for (ItemStack stack : stacks)
		{
			quantities.merge(stack.getName(), stack.getQuantity(), Integer::sum);
		}
		return quantities;
	}

	/**
	 * Canonical identity of an item: its un-noted base ID and name.
	 *
	 * <p>Noted items have their own ids, and the engine matches {@code hasItem} by exact base
	 * id or name, so every snapshot must collapse notes to their base before reaching
	 * {@link PlayerState}.
	 *
	 * <p>Bank placeholders are not collapsed: they pass through with quantity 0, which counts
	 * as nothing. They are flagged for {@link #rememberItemId}, which reads names, not
	 * quantities.
	 */
	static final class CanonicalItem
	{
		final int itemId;
		final String name;
		final boolean placeholder;

		CanonicalItem(int itemId, String name, boolean placeholder)
		{
			this.itemId = itemId;
			this.name = name;
			this.placeholder = placeholder;
		}
	}

	/**
	 * Resolves an item ID to its canonical (un-noted) base ID and name.
	 *
	 * <p>{@link ItemComposition#getNote()} is non-{@code -1} for noted items, and
	 * {@link ItemComposition#getLinkedNoteId()} then points at the un-noted base.
	 *
	 * <p>A bank placeholder ({@link ItemComposition#getPlaceholderTemplateId()}) is flagged,
	 * never resolved. Its id must reach the bank snapshot unchanged: the seed box and rune
	 * pouch are looked for in the bank by id regardless of quantity, so a resolved
	 * placeholder would read as a container the player still owns.
	 */
	private CanonicalItem resolveCanonicalItem(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		if (comp.getNote() != -1)
		{
			ItemComposition linked = itemManager.getItemComposition(comp.getLinkedNoteId());
			return new CanonicalItem(linked.getId(), linked.getName(), false);
		}
		return new CanonicalItem(itemId, comp.getName(), comp.getPlaceholderTemplateId() != -1);
	}

	/**
	 * Infers bank deposits while the deposit box is open, by diffing the inventory against
	 * its snapshot from when the box opened or the previous inference.
	 */
	private void inferDeposits(List<ItemStack> currentInventory)
	{
		if (depositBoxInventorySnapshot == null)
		{
			return;
		}

		Map<String, Integer> current = quantitiesByName(currentInventory);

		// Items that decreased were deposited
		List<ItemStack> deposited = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : depositBoxInventorySnapshot.entrySet())
		{
			String name = entry.getKey();
			int had = entry.getValue();
			int have = current.getOrDefault(name, 0);
			if (have < had)
			{
				deposited.add(new ItemStack(0, had - have, name));
			}
		}

		if (!deposited.isEmpty())
		{
			log.debug("Deposit box inference: {} item type(s) deposited", deposited.size());
			coreEngine.onDepositInferred(deposited);
		}

		depositBoxInventorySnapshot = current;
	}

	// ---------------------------------------------------------------
	// Login: reading the account
	// ---------------------------------------------------------------
	/**
	 * Reads the account into the engine.
	 *
	 * <p>Without a local player there is no profile to load, so this does nothing. A caller
	 * that set {@code loginReady} anyway would let the first save write an unloaded, empty
	 * {@code PlayerState} over the account's real progress.
	 *
	 * <p>A load that throws leaves the engine holding part of the account, and a save would
	 * write that part over the whole profile. So a failure closes the save gate and re-arms the
	 * login gate to try again next tick. After {@link #LOGIN_LOAD_ATTEMPTS} failures the
	 * session stays paused, saving nothing, until the next login. A saved value that does not
	 * parse does not throw: the load keeps it aside and starts that value fresh.
	 *
	 * @return false when there was no local player, the world does not track progress
	 *         ({@link #tracksProgressOn}) or the saved state failed to load, in which case
	 *         the caller must not open the login gate
	 */
	@SuppressWarnings("deprecation") // Skill.OVERALL — no gameval replacement
	private boolean readAccount()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return false;
		}
		// The login gate never arms on these worlds; this covers any caller that
		// invokes it directly.
		if (!tracksProgressOn(client.getWorldType()))
		{
			return false;
		}

		// The RS profile is available now
		try
		{
			coreEngine.loadPersistedState();
		}
		catch (Exception e)
		{
			loginReady = false;
			loginLoadFailures++;
			if (loginLoadFailures < LOGIN_LOAD_ATTEMPTS)
			{
				log.debug("Failed to load saved progress (attempt {}), retrying next tick",
					loginLoadFailures, e);
				loginSnapshotPending = true;
			}
			else
			{
				log.warn("Failed to load saved progress; guide tracking paused until the next "
					+ "login, and nothing is saved", e);
				loginSnapshotPending = false;
			}
			return false;
		}
		loginLoadFailures = 0;

		Map<String, EventApplier.SkillSnapshot> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			skills.put(skill.getName(), new EventApplier.SkillSnapshot(
				client.getRealSkillLevel(skill),
				client.getSkillExperience(skill)));
		}

		// Every quest's state, NOT_STARTED included. The client's answer replaces whatever the
		// saved state held for each quest.
		Map<String, QuestStatus> questStatuses = readQuestStatuses();
		List<String> completedQuests = new ArrayList<>();
		List<String> startedQuests = new ArrayList<>();
		questStatuses.forEach((quest, status) ->
		{
			if (status == QuestStatus.COMPLETED)
			{
				completedQuests.add(quest);
			}
			else if (status == QuestStatus.IN_PROGRESS)
			{
				startedQuests.add(quest);
			}
		});

		ItemContainer invContainer = client.getItemContainer(InventoryID.INV);
		ItemContainer eqContainer = client.getItemContainer(InventoryID.WORN);
		int accountType = client.getVarbitValue(VarbitID.IRONMAN);

		coreEngine.onLogin(
			localPlayer.getName(),
			accountType > 0 && accountType < ACCOUNT_TYPES.length ? ACCOUNT_TYPES[accountType] : AccountType.NORMAL,
			skills,
			client.getVarpValue(VarPlayerID.QP),
			completedQuests,
			startedQuests,
			invContainer != null ? toItemStacks(invContainer.getItems()) : new ArrayList<>(),
			eqContainer != null ? readEquipment(eqContainer.getItems()) : new EnumMap<>(EquipmentSlot.class));
		// A plugin started with the bank already open saw no WidgetLoaded for it.
		coreEngine.onBankOpenChanged(client.getWidget(InterfaceID.Bankmain.UNIVERSE) != null);

		// Scan the vars that guide completion conditions reference, so values set before
		// this session count immediately. Writes playerState directly, then triggers a
		// single resolve pass instead of one per var.
		try
		{
			PlayerState ps = coreEngine.getPlayerState();
			boolean anyChanged = scanVars(ps.getVarps(), requiredVarIds.getVarpIds(), client::getVarpValue);
			anyChanged |= scanVars(ps.getVarbits(), requiredVarIds.getVarbitIds(), client::getVarbitValue);

			// Records never-started quests, which onLogin leaves out.
			for (Map.Entry<String, QuestStatus> quest : questStatuses.entrySet())
			{
				anyChanged |= ps.getQuests().put(quest.getKey(), quest.getValue()) != quest.getValue();
			}

			// Read varbits that no completion condition names. Without this, a container
			// filled or a boat bought in an earlier session would not count until its varbit
			// next changed. They cover off-inventory storage (tool leprechaun, plank sack, bow
			// string spool) that ANYWHERE item counts include, and boat ownership, which gates
			// whether a persisted cargo hold counts.
			anyChanged |= scanVars(ps.getVarbits(), ResolutionEngine.LEPRECHAUN_STORAGE_VARBIT_IDS, client::getVarbitValue);
			anyChanged |= scanVars(ps.getVarbits(), PlankSack.CONTENT_VARBIT_IDS, client::getVarbitValue);
			anyChanged |= scanVars(ps.getVarbits(), BowStringSpool.CONTENT_VARBIT_IDS, client::getVarbitValue);
			anyChanged |= scanVars(ps.getVarbits(), BoatVarbits.ownershipIds(), client::getVarbitValue);

			// The kingdom's state, which no guide step references. Primed explicitly for the
			// same reason as the storage varbits above: no completion condition names them.
			anyChanged |= scanVars(ps.getVarbits(), Ints.asList(KingdomTracker.VARBIT_IDS), client::getVarbitValue);
			coreEngine.primeKingdomFromLogin();

			anyChanged |= scanQuestProgress(ps, QuestVarbits.getVarbitMap(), "varbit", client::getVarbitValue);
			anyChanged |= scanQuestProgress(ps, QuestVarbits.getVarplayerMap(), "varp", client::getVarpValue);
			loginQuestProgressLogged = true;

			// Runes already in the pouch count toward rune hasItem checks from login.
			updateRunePouch();

			if (anyChanged)
			{
				coreEngine.triggerResolve();
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to scan initial var state", e);
		}
		// The profile loaded. A failed var scan only leaves some varbits unread, which the
		// live handlers repair.
		return true;
	}

	/**
	 * Re-reads the rune pouch contents from its varbits and pushes them into the engine.
	 * Must run on the client thread.
	 */
	private void updateRunePouch()
	{
		EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runeEnum == null)
		{
			return;
		}
		Map<Integer, Integer> contents = RunePouch.read(client::getVarbitValue, runeEnum::getIntValue);
		coreEngine.onRunePouchChanged(contents);
	}

	// ---------------------------------------------------------------
	// Menu clicks
	// ---------------------------------------------------------------
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (coreEngine == null || !loginReady)
		{
			return;
		}

		captureBirdhouseInteraction(event);

		// Observed only: the click goes through untouched.
		if (event.isItemOp())
		{
			lampChooser.onItemOp(event.getItemId(), event.getMenuOption(), client.getTickCount());
		}

		// Only NPC entries carry an NPC. Their examine and use-on options are not tracked
		// actions, so the NPC's own menu options are the only ones that pass.
		String action = event.getMenuOption();
		NPC npc = event.getMenuEntry().getNpc();
		if (npc != null && NpcInteractions.isTrackedAction(action))
		{
			// Matched by name: one NPC can have many ids across worlds and quest stages.
			coreEngine.onNpcInteraction(npc.getName(), action);
		}
	}

	// ---------------------------------------------------------------
	// PER-TICK SAMPLERS (slayer task, region)
	// ---------------------------------------------------------------
	/**
	 * Sample the four Slayer task vars and hand them to the engine as one reading.
	 *
	 * <p>Both streaks are sampled because which one moves depends on the master: a
	 * Krystilia task advances only the Wilderness counter.
	 */
	private void handleSlayerTask()
	{
		coreEngine.onSlayerTaskSample(
			client.getVarpValue(SlayerTaskTracker.VARP_SLAYER_COUNT),
			client.getVarbitValue(SlayerTaskTracker.VARBIT_SLAYER_MASTER),
			client.getVarbitValue(SlayerTaskTracker.VARBIT_TASK_STREAK),
			client.getVarbitValue(SlayerTaskTracker.VARBIT_WILDERNESS_STREAK));
	}

	private void handleRegionTracking()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		WorldPoint location = localPlayer.getWorldLocation();
		int currentRegionId = location.getRegionID();

		regionTracker.update(currentRegionId)
			.ifPresent(coreEngine::onRegionChanged);
	}

}
