package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.engine.NavigatorState;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.StepContext;
import com.stepwise.guide.StepId;
import com.stepwise.persistence.StepwisePersistence;
import com.stepwise.snapshot.CurrentStepSummary;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.GuidePosition;
import com.stepwise.snapshot.ItemNeededStatus;
import java.awt.BorderLayout;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Main side panel for Stepwise, registered via NavigationButton in StepwisePlugin.startUp().
 *
 * <p>A fixed header (title, step counter, chapter and section) sits above a scrolling
 * column that stacks, top to bottom: the Step Navigator, recent history, the preview banner
 * while a step is previewed, the entry block, the current step card, the restock strip,
 * in-progress steps, upcoming steps, timers, reset controls and the version footer.
 */
public class StepwisePanel extends PluginPanel
{
	/** Scrollbar width plus the right border and padding of the RuneLite sidebar icons. */
	private static final int SIDEBAR_TRIM = 14;
	/** The scrolling column's content width. Anything wider runs under the sidebar icons. */
	static final int CONTENT_WIDTH = PluginPanel.PANEL_WIDTH - SIDEBAR_TRIM;

	private static final Color COLOR_MUTED = PanelColors.HEADER;
	private static final Color COLOR_DIVIDER = new Color(70, 70, 70);
	private static final Color COLOR_SUBTLE = PanelColors.SUBTLE;
	private static final DateTimeFormatter GUIDE_DATE =
		DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private CoreEngine coreEngine;
	private IconProvider iconProvider;
	private StepwiseGuide guide;
	private StepwisePersistence persistence;
	private String pluginVersion;

	private final JPanel headerPanel;
	private final EntryBlock entryBlock;
	private final ItemsNeededView itemsNeededView;
	private final JPanel currentStepContainer;
	private final RestockStrip restockStrip;
	private final InProgressPanel inProgressPanel;
	private final UpcomingPanel upcomingPanel;
	private final HistoryPanel historyPanel;
	private final TimerPanel timerPanel;
	private final RouteDeficitPanel routeDeficitPanel;
	private final JLabel versionFooter;

	// Hidden on the welcome page, which has no account data to reset.
	private final JPanel dataControls;

	private final JLabel counterText;
	private final JLabel chapterText;
	private final JTextArea sectionText;

	private long lastSnapshotVersion = -1;
	/**
	 * Builds the welcome panel once per stretch without a snapshot. {@link #refresh()} runs
	 * every tick, and a rebuilt welcome text re-wraps from one line to three each time.
	 */
	private final RebuildGuard waitingGuard = new RebuildGuard();
	private String lastDisplayedStepId;
	/** Whether the user collapsed the guidance section on the active step. */
	private boolean guidanceCollapsed;
	/** The active step card, kept for in-place progress updates. */
	private StepCardPanel currentStepCard;

	/**
	 * Wall-clock ticker for the Timers section. Game ticks stop when logged out, and the
	 * bird house, Tears of Guthix and Miscellania countdowns run on real time, so this keeps
	 * them moving. Fires on the EDT, independent of the snapshot-version gate in
	 * {@link #refresh()}.
	 */
	private final javax.swing.Timer timerTicker;

	/**
	 * The live engine's snapshot version when the player logged out, or -1 while not logged
	 * out. While set, the panel shows the welcome page instead of that engine's snapshot,
	 * which still holds the account that left. The next login builds a snapshot under a new
	 * version, which ends the mode.
	 *
	 * <p>Volatile: written by {@link #showLoggedOut}, {@link #showLoggedIn}, {@link #refresh}
	 * and {@link #dispose} on the caller's thread, and read on the EDT by the timer ticker and
	 * {@link #showLastSession}.
	 */
	private volatile long loggedOutAtVersion = -1;
	/**
	 * Counts entries to and exits from the logged-out page, so a last session built for one
	 * stretch is dropped if it arrives in another.
	 */
	private final AtomicInteger loggedOutGeneration = new AtomicInteger();
	/**
	 * The last account's engine, built over its saved state, or null. Shown read-only on the
	 * welcome page. EDT only: handed over through the EDT queue and touched nowhere else.
	 */
	private CoreEngine lastSession;
	/** The last session's entry: title and guide text only, never the items or route chips. */
	private final EntryBlock lastSessionEntry;
	private final TimerPanel lastSessionTimers;
	/** Holds the preview banner while a step is previewed, and nothing otherwise. */
	private final JPanel previewSlot;
	/** Holds the Step Navigator for the loaded guide. */
	private final JPanel navigatorSlot;
	/** Null until a guide is set. EDT only. */
	private StepNavigator navigator;
	/**
	 * What the navigator draws for the account shown, copied on the thread that drives its
	 * engine; null with no account shown. EDT only.
	 */
	private NavigatorState navigatorProgress;
	/** Keyed on the player's step id, or null with no banner. EDT only. */
	private final RebuildGuard previewGuard = new RebuildGuard();
	/** Whether the last rebuild showed a preview, so leaving one redraws the card. EDT only. */
	private boolean lastDisplayedPreview;
	/**
	 * The player's own step under the last preview, so a move redraws the previewed card and
	 * its Move here. EDT only.
	 */
	private String lastDisplayedLiveStepId;

	public StepwisePanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Extra right padding clears the RuneLite sidebar icon strip.
		headerPanel = new JPanel();
		headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 20));

		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);

		JLabel titleLabel = new JLabel("STEPWISE");
		titleLabel.setForeground(PanelColors.BRAND_RED_LIGHT);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleRow.add(titleLabel, BorderLayout.WEST);

		counterText = PanelSections.smallLabel(" ", PanelColors.BRAND_IRON_LIGHT);
		titleRow.add(counterText, BorderLayout.EAST);

		headerPanel.add(titleRow);
		headerPanel.add(Box.createVerticalStrut(2));

		// Chapter: one line, cut with an ellipsis and whole on hover, so a long title does not
		// change the header's height.
		chapterText = PanelSections.smallLabel(" ", COLOR_MUTED);
		chapterText.setAlignmentX(LEFT_ALIGNMENT);
		headerPanel.add(chapterText);
		headerPanel.add(Box.createVerticalStrut(1));

		// Section: numbered and wrapping, so it is never cut. showHeading writes it only on
		// change, so the header re-measures at a section boundary and never per tick.
		sectionText = muted(" ");
		headerPanel.add(sectionText);

		// Left-aligned like every other header row. A centred child would shift the whole
		// column once the welcome page hides the wrapping section line that fills the width.
		headerPanel.add(PanelColors.rule(PanelColors.BRAND_RED, 2));

		// FixedWidthPanel fixes the width so JTextAreas know where to wrap. Placing it at
		// BorderLayout.NORTH (below) packs it to the top at its preferred height.
		JPanel contentPanel = new FixedWidthPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		itemsNeededView = new ItemsNeededView();

		currentStepContainer = PanelSections.column();
		currentStepContainer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		restockStrip = new RestockStrip();
		inProgressPanel = new InProgressPanel();
		upcomingPanel = new UpcomingPanel();
		historyPanel = new HistoryPanel();
		timerPanel = new TimerPanel();
		routeDeficitPanel = new RouteDeficitPanel();
		entryBlock = new EntryBlock(itemsNeededView, routeDeficitPanel);
		lastSessionEntry = EntryBlock.textOnly();
		lastSessionTimers = new TimerPanel();

		previewSlot = PanelSections.column();

		navigatorSlot = PanelSections.column();

		contentPanel.add(navigatorSlot);
		contentPanel.add(historyPanel);
		contentPanel.add(previewSlot);
		contentPanel.add(entryBlock);
		contentPanel.add(currentStepContainer);
		contentPanel.add(restockStrip);
		contentPanel.add(inProgressPanel);
		contentPanel.add(upcomingPanel);
		contentPanel.add(timerPanel);
		dataControls = buildResetControlsPanel();
		contentPanel.add(dataControls);

		versionFooter = PanelSections.smallLabel(" ", COLOR_SUBTLE);
		versionFooter.setHorizontalAlignment(JLabel.RIGHT);
		versionFooter.setAlignmentX(LEFT_ALIGNMENT);
		versionFooter.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
		versionFooter.setVisible(false);

		contentPanel.add(rightAligned(versionFooter));

		JPanel northWrapper = new ScrollAnchoredPanel();
		northWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		northWrapper.add(contentPanel, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane(northWrapper);
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(headerPanel, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		// Started in addNotify, not here: a panel built but never shown (one whose host
		// fails during construction) would otherwise tick forever through a dropped classloader.
		timerTicker = new javax.swing.Timer(15_000, e -> refreshTimers());
	}

	/** Ticks the timer countdowns only while the panel is on screen. */
	@Override
	public void addNotify()
	{
		super.addNotify();
		timerTicker.start();
	}

	@Override
	public void removeNotify()
	{
		timerTicker.stop();
		super.removeNotify();
	}

	public void setCoreEngine(CoreEngine coreEngine)
	{
		this.coreEngine = coreEngine;
		this.lastSnapshotVersion = -1;
		// Force a full card rebuild. A new engine may carry changed guide content under
		// the same active step ID.
		this.lastDisplayedStepId = null;
	}

	public void setIconProvider(IconProvider iconProvider)
	{
		this.iconProvider = iconProvider;
	}

	public void setGuide(StepwiseGuide guide)
	{
		this.guide = guide;
		forgetWaitingBuild();
		SwingUtilities.invokeLater(() ->
		{
			navigatorSlot.removeAll();
			navigator = guide != null ? new StepNavigator(guide, this) : null;
			if (navigator != null)
			{
				navigatorSlot.add(navigator);
				navigator.refresh();
			}
			navigatorSlot.revalidate();
		});
	}

	public void setPersistence(StepwisePersistence persistence)
	{
		this.persistence = persistence;
		forgetWaitingBuild();
	}

	/**
	 * The welcome panel shows the guide's title, size and version and the last session, so a
	 * guide reload while no snapshot exists must rebuild it. Posted to the EDT, which owns the
	 * guard. It runs ahead of the next refresh's rebuild, which queues behind it.
	 */
	private void forgetWaitingBuild()
	{
		SwingUtilities.invokeLater(waitingGuard::reset);
	}

	public void setPluginVersion(String pluginVersion)
	{
		this.pluginVersion = pluginVersion;
	}

	/**
	 * Shows the welcome state until the first refresh(). Call after the guide, persistence
	 * and version setters, which the empty state reads.
	 */
	public void showInitialState()
	{
		updateWaiting();
	}

	/**
	 * Refreshes the panel from the engine's last snapshot, skipping a refresh where its
	 * version has not moved. Call from the thread that drives the engine. The rebuild runs
	 * on the EDT.
	 */
	public void refresh()
	{
		if (coreEngine == null)
		{
			return;
		}

		DashboardSnapshot live = coreEngine.getLastSnapshot();
		DashboardSnapshot preview = coreEngine.getPreviewSnapshot();
		DashboardSnapshot snapshot = preview != null && live != null ? preview : live;
		long currentVersion = coreEngine.getSnapshotVersion();
		if (loggedOutAtVersion >= 0)
		{
			if (snapshot == null || currentVersion == loggedOutAtVersion)
			{
				updateWaiting();
				return;
			}
			// The next login's first snapshot.
			leaveLoggedOut();
		}
		if (snapshot == null)
		{
			updateWaiting();
			return;
		}

		if (currentVersion == lastSnapshotVersion)
		{
			return;
		}
		lastSnapshotVersion = currentVersion;

		DashboardSnapshot liveWhilePreviewing = snapshot != live ? live : null;
		NavigatorState progress = NavigatorState.of(coreEngine);
		SwingUtilities.invokeLater(() ->
		{
			navigatorProgress = progress;
			rebuildFromSnapshot(snapshot, liveWhilePreviewing);
		});
	}

	/**
	 * Re-renders only the Timers section, bypassing the snapshot-version gate in
	 * {@link #refresh()} because the countdowns move on wall-clock time.
	 */
	private void refreshTimers()
	{
		if (loggedOutAtVersion >= 0)
		{
			// The live engine's timers belong to the account that left, so they stay hidden.
			// The ticker is a Swing timer, so this is already the EDT.
			refreshLastSessionTimers();
			return;
		}
		if (coreEngine == null)
		{
			return;
		}
		DashboardSnapshot snapshot = coreEngine.getLastSnapshot();
		SwingUtilities.invokeLater(() -> timerPanel.rebuild(snapshot, coreEngine, iconProvider));
	}

	/**
	 * Moves the last session's countdowns on. Every row reads the wall clock itself, so a
	 * rebuild is enough; the last session's engine never resolves again. EDT only.
	 */
	private void refreshLastSessionTimers()
	{
		CoreEngine session = lastSession;
		if (session == null || loggedOutAtVersion < 0)
		{
			return;
		}
		lastSessionTimers.rebuild(session.getLastSnapshot(), session, iconProvider);
	}

	/**
	 * Shows the welcome page in place of the live engine's snapshot, which still holds the
	 * account that left, until the next login builds a new one. Call at logout, after the
	 * session summary is saved, and at start-up with nobody logged in.
	 *
	 * <p>Call from the thread that calls {@link #refresh}: the client thread in the plugin.
	 *
	 * @return the token {@link #showLastSession} takes, which goes stale at the next login or
	 *         logout
	 */
	public int showLoggedOut()
	{
		loggedOutAtVersion = coreEngine != null ? coreEngine.getSnapshotVersion() : 0;
		int generation = loggedOutGeneration.incrementAndGet();
		SwingUtilities.invokeLater(() ->
		{
			lastSession = null;
			navigatorProgress = null;
			// The summary on the resume card was just saved, so the page must be rebuilt.
			waitingGuard.reset();
			showWaiting();
		});
		return generation;
	}

	/**
	 * Shows the last account's current step, its entry and its timers on the welcome page,
	 * read-only. Dropped when {@code generation} is stale, so a session built just before a
	 * login never reaches the panel. Safe to call from any thread.
	 *
	 * @param generation the token from {@link #showLoggedOut}
	 * @param session    an engine over the last account's saved state, which nothing else
	 *                   touches from here on
	 */
	public void showLastSession(int generation, CoreEngine session)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (generation != loggedOutGeneration.get() || loggedOutAtVersion < 0)
			{
				return;
			}
			lastSession = session;
			navigatorProgress = NavigatorState.of(session);
			showWaiting();
		});
	}

	/**
	 * What the Step Navigator draws: the last session's account on the welcome page, the
	 * live one otherwise, or null with neither. EDT only.
	 */
	@Nullable
	NavigatorState navigatorProgress()
	{
		return navigatorProgress;
	}

	/**
	 * Previews {@code stepId}, or ends the preview with null, in the account the panel shows.
	 * The last session's engine never resolves again and no tick runs while logged out, so
	 * the welcome page redraws here. EDT only.
	 */
	void previewStep(StepId stepId)
	{
		CoreEngine engine = loggedOutAtVersion >= 0 ? lastSession : coreEngine;
		if (engine == null)
		{
			return;
		}
		engine.previewStep(stepId);
		if (engine == lastSession)
		{
			navigatorProgress = NavigatorState.of(engine);
			showWaiting();
		}
	}

	/**
	 * Ends the logged-out page once an account has loaded, so the next {@link #refresh}
	 * shows the live snapshot.
	 *
	 * <p>Needed beside the version check in {@link #refresh}: the engine moves its version
	 * only when the snapshot's content changes, and an account logging straight back in can
	 * build the same snapshot it left with. Call from the thread that calls {@link #refresh}.
	 */
	public void showLoggedIn()
	{
		if (loggedOutAtVersion >= 0)
		{
			leaveLoggedOut();
			lastSnapshotVersion = -1;
		}
	}

	/** Ends the logged-out page. The caller rebuilds from the new snapshot straight after. */
	private void leaveLoggedOut()
	{
		loggedOutAtVersion = -1;
		loggedOutGeneration.incrementAndGet();
		SwingUtilities.invokeLater(() -> lastSession = null);
	}

	/**
	 * Stops the wall-clock ticker. Call when tearing the panel down: a panel still attached
	 * at shutdown would otherwise keep it running.
	 */
	public void dispose()
	{
		timerTicker.stop();
		// A last session still being built would otherwise land on a panel nobody shows.
		loggedOutGeneration.incrementAndGet();
		loggedOutAtVersion = -1;
	}

	/**
	 * Shows the welcome page while the engine has no snapshot: before the first login, and
	 * for the moment between a guide reset and the re-scan that follows it in the same tick.
	 * Also from logout to the next login's first snapshot (see {@link #showLoggedOut}).
	 */
	private void updateWaiting()
	{
		SwingUtilities.invokeLater(this::showWaiting);
	}

	/**
	 * {@link #updateWaiting}'s work. Built once per stretch and per last session, so a tick
	 * on the welcome page never re-creates its wrapping text. EDT only.
	 */
	private void showWaiting()
	{
		refreshNavigator();
		CoreEngine session = lastSession;
		if (!waitingGuard.shouldRebuild(session != null
			? Arrays.asList(session, session.getPreviewSnapshot()) : Boolean.TRUE))
		{
			return;
		}
		counterText.setText(" ");
		showHeading(" ", " ");
		showWelcomeChrome(true);
		itemsNeededView.rebuild(null, null, null);
		int containerWidth = currentStepContainer.getWidth();
		currentStepContainer.removeAll();
		if (session != null)
		{
			addLastSessionPage(session, containerWidth);
		}
		else
		{
			currentStepContainer.add(buildEmptyState());
		}
		// The step card and history are gone. A guide reset can latch straight back to the
		// same step, so forget it, or the next snapshot updates a detached card in place.
		currentStepCard = null;
		lastDisplayedStepId = null;
		// A preview open at logout belongs to the account that left.
		previewSlot.removeAll();
		previewGuard.shouldRebuild(null);
		lastDisplayedPreview = false;
		lastDisplayedLiveStepId = null;
		// No restock chips on the welcome page: nobody is logged in to restock.
		restockStrip.update(null, iconProvider);
		inProgressPanel.rebuild(null, null, null);
		upcomingPanel.rebuild(null, null, null);
		historyPanel.rebuild(null, null, null);
		timerPanel.rebuild(null, null, null);
		routeDeficitPanel.rebuild(null, null, null, iconProvider);
		entryBlock.update(null, null, null);
		revalidate();
		repaint();
	}

	/**
	 * Strips the panel down to the welcome page while no account is loaded, or puts the
	 * heading lines and Data section back. The Data section works on an account, so
	 * before login it would offer a reset with nothing to reset. Swing ignores a setVisible
	 * that changes nothing, so a per-tick call costs no layout pass.
	 */
	private void showWelcomeChrome(boolean welcome)
	{
		chapterText.setVisible(!welcome);
		sectionText.setVisible(!welcome);
		dataControls.setVisible(!welcome);
		// The welcome page carries its own version line.
		versionFooter.setVisible(!welcome && guide != null && guide.getVersion() != null);
	}

	/**
	 * @param live the player's own snapshot when {@code snapshot} is a preview of another
	 *             step, else null
	 */
	private void rebuildFromSnapshot(DashboardSnapshot snapshot, DashboardSnapshot live)
	{
		waitingGuard.reset();
		showWelcomeChrome(false);
		GuidePosition pos = snapshot.getGuidePosition();
		String currentStepId = pos != null ? pos.getStepId() : null;
		boolean previewing = live != null;
		String liveStepId = live != null && live.getGuidePosition() != null
			? live.getGuidePosition().getStepId() : null;
		// A previewed card is also redrawn when the player's own step moves, which decides
		// whether it offers Move here. Otherwise it stays put: a per-tick rebuild would disarm
		// a Move here waiting for its confirming click.
		boolean stepChanged = !Objects.equals(currentStepId, lastDisplayedStepId)
			|| previewing != lastDisplayedPreview
			|| previewing && !Objects.equals(liveStepId, lastDisplayedLiveStepId);
		lastDisplayedStepId = currentStepId;
		lastDisplayedPreview = previewing;
		lastDisplayedLiveStepId = previewing ? liveStepId : null;

		boolean showBanner = previewing && currentStepId != null;
		if (previewGuard.shouldRebuild(showBanner ? liveStepId : null))
		{
			previewSlot.removeAll();
			if (showBanner)
			{
				previewSlot.add(PreviewBar.banner(liveStepId, this::previewStep));
			}
		}

		if (pos != null)
		{
			counterText.setText(pos.stepCounter());

			String chapter = pos.getChapter() != null
				? TextMarkup.stripMarkup(pos.getChapter()) : "";
			String section = formatSectionLabel(pos.getSection() != null
				? TextMarkup.stripMarkup(pos.getSection()) : "");
			// " " for an empty line, matching the waiting state, so switching between them is no change.
			showHeading(chapter.isEmpty() ? " " : chapter, section.isEmpty() ? " " : section);
		}
		else
		{
			counterText.setText(" ");
			showHeading(" ", " ");
		}

		if (stepChanged)
		{
			guidanceCollapsed = false;
		}

		// The panels below update on every snapshot, not only on a step change. XP, coins,
		// items and diaries all move independently of guide position, as does a Mark Done or
		// Skip made from the In Progress list, which Recent must offer to undo. Each panel's
		// own RebuildGuard makes an unchanged refresh cheap.
		historyPanel.rebuild(snapshot, coreEngine, iconProvider);
		inProgressPanel.rebuild(snapshot, coreEngine, iconProvider);
		upcomingPanel.rebuild(snapshot, coreEngine, iconProvider);
		routeDeficitPanel.rebuild(snapshot, coreEngine, guide, iconProvider);

		CurrentStepSummary current = snapshot.getCurrentStep();

		StepContext activeCtx = currentStepId != null
			? coreEngine.getGuide().stepContextMap().get(new StepId(currentStepId)) : null;
		String entryId = activeCtx != null ? activeCtx.getEntryId() : null;
		List<ItemNeededStatus> itemsNeeded = current != null ? current.getItemsNeeded() : null;
		itemsNeededView.rebuild(entryId, itemsNeeded, iconProvider);
		// Must follow the items view and route panel updates: the chips read their state.
		entryBlock.update(activeCtx, currentStepId, coreEngine.getGuide());

		if (!stepChanged && currentStepCard != null && current != null)
		{
			// Same step: update in place so the card's wrapping text areas are not recreated.
			currentStepCard.updateCriteria(current.getCriteria(), current.isCriteriaAlternatives());
			currentStepCard.updatePreSteps(current.getPreSteps());
		}
		else
		{
			int containerWidth = currentStepContainer.getWidth();
			currentStepContainer.removeAll();
			currentStepCard = null;

			if (current != null)
			{
				// A previewed card has no engine, so it offers no Mark Done or Skip. It gets the
				// arrows where the current card has them, and Move here in Mark Done's place.
				StepCardPanel card = currentCard(previewing ? null : coreEngine, current,
					guidanceCollapsed, collapsed -> guidanceCollapsed = collapsed);
				if (previewing)
				{
					card.showAsPreview(PreviewBar.ACCENT);
					card.addPaging(PreviewBar.moveHere(currentStepId, liveStepId,
							coreEngine.getGuide(), coreEngine),
						PreviewBar.paging(currentStepId, coreEngine.getGuide(), this::previewStep));
				}
				card.presize(containerWidth);
				currentStepContainer.add(card);
				currentStepCard = card;
			}
			else if (pos != null)
			{
				JTextArea instrText = StepCardPanel.textArea(
					pos.getInstruction(), Color.WHITE, FontManager.getRunescapeFont());
				instrText.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
				currentStepContainer.add(instrText);
			}
		}

		restockStrip.update(current != null ? current.getAssumptionNudges() : null, iconProvider);
		timerPanel.rebuild(snapshot, coreEngine, iconProvider);

		if (guide != null && guide.getVersion() != null)
		{
			String name = shortGuideName();
			versionFooter.setText("Unofficial " + name + ", " + versionText(guide.getVersion()));
			versionFooter.setVisible(true);
		}

		refreshNavigator();
		revalidate();
		repaint();
	}

	/** Redraws the Step Navigator when what it shows has moved. EDT only. */
	private void refreshNavigator()
	{
		if (navigator != null)
		{
			navigator.refresh();
		}
	}

	/**
	 * The welcome page shown while no account is loaded: where the last account left off, or
	 * a first-run welcome when there has been none, then the guide and its authors.
	 */
	private JPanel buildEmptyState()
	{
		JPanel panel = welcomeColumn(12, 12);
		addWelcome(panel);
		addCreditBlock(panel);
		return panel;
	}

	/**
	 * The welcome page with the last account's progress below its resume card: the current
	 * step's entry and card, then its timers, all read-only, then the guide credit. The
	 * entry, card and timers span the column like a logged-in account's own. The card's
	 * arrows preview other steps in its place, under the preview banner. The card offers no
	 * Move here, since nothing done while logged out is saved.
	 */
	private void addLastSessionPage(CoreEngine session, int containerWidth)
	{
		JPanel top = welcomeColumn(12, 8);
		addWelcome(top);
		currentStepContainer.add(top);

		DashboardSnapshot live = session.getLastSnapshot();
		DashboardSnapshot preview = session.getPreviewSnapshot();
		DashboardSnapshot snapshot = preview != null ? preview : live;
		GuidePosition pos = snapshot != null ? snapshot.getGuidePosition() : null;
		String stepId = pos != null ? pos.getStepId() : null;
		if (preview != null && stepId != null)
		{
			GuidePosition livePos = live != null ? live.getGuidePosition() : null;
			currentStepContainer.add(PreviewBar.banner(
				livePos != null ? livePos.getStepId() : null, this::previewStep));
		}
		StepContext context = stepId != null
			? session.getGuide().stepContextMap().get(new StepId(stepId)) : null;
		lastSessionEntry.update(context, stepId, session.getGuide());
		currentStepContainer.add(lastSessionEntry);

		StepCardPanel card = lastSessionCard(snapshot, pos);
		if (card != null)
		{
			if (preview != null)
			{
				card.showAsPreview(PreviewBar.ACCENT);
			}
			if (stepId != null)
			{
				card.addPaging(null, PreviewBar.paging(stepId, session.getGuide(), this::previewStep));
			}
			card.presize(containerWidth);
			currentStepContainer.add(card);
		}

		lastSessionTimers.rebuild(live, session, iconProvider);
		currentStepContainer.add(lastSessionTimers);

		JPanel bottom = welcomeColumn(0, 12);
		addCreditBlock(bottom);
		currentStepContainer.add(bottom);
	}

	/**
	 * The last session's current step, without Mark Done, Skip or Undo: a card with no engine
	 * has no buttons, and nothing done while logged out would be saved. Null without a
	 * current step.
	 */
	private StepCardPanel lastSessionCard(DashboardSnapshot snapshot, GuidePosition pos)
	{
		CurrentStepSummary current = snapshot != null ? snapshot.getCurrentStep() : null;
		if (current != null)
		{
			return currentCard(null, current, false, null);
		}
		// No current step was saved: fall back to the step the guide position names.
		if (pos != null && pos.getStepId() != null)
		{
			return new StepCardPanel(null, pos.getStepId(), pos.getInstruction(), null,
				null, true, iconProvider);
		}
		return null;
	}

	/** The current step's card; with a null engine it has no buttons. */
	private StepCardPanel currentCard(CoreEngine engine, CurrentStepSummary current,
		boolean guidanceCollapsed, Consumer<Boolean> onGuidanceToggle)
	{
		StepCardPanel card = new StepCardPanel(engine, current.getStepId(),
			current.getInstruction(), current.getGuidance(), current.getCriteria(), true,
			iconProvider, current.getPreSteps(), guidanceCollapsed, onGuidanceToggle,
			current.isCriteriaAlternatives(), current.isCompletedByClick());
		card.setFromNowOn(current.getFromNowOn());
		return card;
	}

	/** One stretch of the welcome page's padded column. */
	private static JPanel welcomeColumn(int top, int bottom)
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(top, 10, bottom, 10));
		panel.setAlignmentX(LEFT_ALIGNMENT);
		return panel;
	}

	/** Where the last account left off, or a first-run welcome when there has been none. */
	private void addWelcome(JPanel panel)
	{
		StepwisePersistence.SessionSummary session = persistence != null
			? persistence.loadSessionSummary() : null;
		if (session != null)
		{
			addResumeCard(panel, lastSession != null && guide != null
				? withProgressFrom(session, lastSession, guide) : session);
		}
		else
		{
			addFirstRunWelcome(panel);
		}
	}

	/** The divider, the guide credit and the plugin version that close the welcome page. */
	private void addCreditBlock(JPanel panel)
	{
		panel.add(Box.createVerticalStrut(14));
		panel.add(PanelColors.divider(COLOR_DIVIDER));
		panel.add(Box.createVerticalStrut(10));
		addGuideCredit(panel);

		if (pluginVersion != null)
		{
			JPanel versionRow = rightAligned(
				PanelSections.smallLabel("Stepwise v" + pluginVersion, COLOR_SUBTLE));
			versionRow.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
			panel.add(versionRow);
		}
	}

	/** A full-width transparent row holding {@code label} at its right edge. */
	private static JPanel rightAligned(JLabel label)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.add(label, BorderLayout.EAST);
		return row;
	}

	/**
	 * {@code summary} with its progress counted from the last session's own saved steps. The
	 * play-time figures come from the saved steps, not from the summary. A guide without
	 * timings gives none, so the card counts steps.
	 */
	static StepwisePersistence.SessionSummary withProgressFrom(StepwisePersistence.SessionSummary summary,
		CoreEngine session, StepwiseGuide guide)
	{
		Set<StepId> done = session.getCompletedSteps();
		Double totalMinutes = guide.getTotalMinutes();
		Double minutesDone = totalMinutes != null ? Double.valueOf(guide.minutesDone(done)) : null;
		return new StepwisePersistence.SessionSummary(summary.getPlayerName(), summary.getStepIndex(),
			guide.allSteps().size(), summary.getChapter(), done.size(), minutesDone, totalMinutes,
			summary.getRsProfileKey());
	}

	/** The last account's name, chapter and progress, saved at its last logout. */
	private static void addResumeCard(JPanel panel, StepwisePersistence.SessionSummary session)
	{
		panel.add(StepCardPanel.textArea(session.getPlayerName(),
			Color.WHITE, FontManager.getRunescapeBoldFont()));
		if (session.getChapter() != null && !session.getChapter().isEmpty())
		{
			panel.add(Box.createVerticalStrut(2));
			panel.add(muted(TextMarkup.stripMarkup(session.getChapter())));
		}

		// Completed rather than the current step's index: a player who skipped ahead has a
		// cursor well past the steps they have done. Clamped because the saved count can
		// exceed the loaded guide's step total.
		int total = session.getTotalSteps();
		if (total > 0)
		{
			int done = Math.min(session.getCompletedCount(), total);
			// By the guide's own timings when the summary has them, since one step can be a
			// minute's errand and the next a week of training. By step count otherwise.
			double fraction = (double) done / total;
			String tooltip = null;
			// The percentage names what it measures, so it never reads as the step count.
			String measure = "% done";
			if (session.getMinutesDone() != null && session.getTotalMinutes() != null
				&& session.getTotalMinutes() > 0)
			{
				measure = "% of play time";
				double totalMinutes = session.getTotalMinutes();
				double minutesDone = Math.min(session.getMinutesDone(), totalMinutes);
				fraction = minutesDone / totalMinutes;
				double minutesLeft = totalMinutes - minutesDone;
				// Under half a minute would read "0m left" beside a bar short of full.
				tooltip = minutesLeft >= 0.5 ? formatPlayTime(minutesLeft) + " of play time left" : null;
			}
			panel.add(Box.createVerticalStrut(8));
			TimerProgressBar bar = new TimerProgressBar(fraction, COLOR_MUTED);
			bar.setAlignmentX(LEFT_ALIGNMENT);
			bar.setToolTipText(tooltip);
			panel.add(bar);
			panel.add(Box.createVerticalStrut(4));
			JTextArea progress = muted(
				// The epsilon keeps 0.29 * 100 = 28.999... from reading as 28%.
				(int) Math.floor(fraction * 100 + 1e-9) + measure
					+ String.format(Locale.ROOT, "  \u00B7  %,d of %,d steps", done, total));
			progress.setToolTipText(tooltip);
			panel.add(progress);
		}

		panel.add(Box.createVerticalStrut(12));
		panel.add(StepCardPanel.textArea("Log in to pick up where you left off.",
			Color.WHITE, FontManager.getRunescapeFont()));
	}

	/** "3d 4h", "5h" or "40m": days and hours, or minutes under an hour. */
	static String formatPlayTime(double minutes)
	{
		long total = Math.max(0, Math.round(minutes));
		long days = total / (24 * 60);
		long hours = total % (24 * 60) / 60;
		if (days > 0)
		{
			return hours > 0 ? days + "d " + hours + "h" : days + "d";
		}
		return hours > 0 ? hours + "h" : total + "m";
	}

	private static void addFirstRunWelcome(JPanel panel)
	{
		panel.add(StepCardPanel.textArea("Welcome to Stepwise",
			ColorScheme.BRAND_ORANGE, FontManager.getRunescapeBoldFont()));
		panel.add(Box.createVerticalStrut(6));
		panel.add(muted("Stepwise is an unofficial plugin for the BRUHsailer Ironman guide. It "
			+ "checks steps off as you complete them in game."));
		panel.add(Box.createVerticalStrut(12));
		panel.add(StepCardPanel.textArea("Log in to start.",
			Color.WHITE, FontManager.getRunescapeFont()));
		panel.add(Box.createVerticalStrut(6));
		panel.add(muted("If you have already done part of the guide, Stepwise finds your place "
			+ "when you log in."));
	}

	private static JTextArea muted(String text)
	{
		return StepCardPanel.textArea(text, COLOR_MUTED, FontManager.getRunescapeSmallFont());
	}

	/** The guide's title, size and version, and the guide's authors. */
	private void addGuideCredit(JPanel panel)
	{
		String title = guide != null && guide.getTitle() != null ? guide.getTitle() : "BRUHsailer";
		panel.add(StepCardPanel.textArea(title, Color.WHITE, FontManager.getRunescapeFont()));
		if (guide != null)
		{
			String info = String.format(Locale.ROOT, "%,d steps", guide.allSteps().size());
			if (guide.getVersion() != null)
			{
				// The BRUHsailer document's own version, the date stamp it puts on each chapter.
				info += "  \u00B7  " + versionText(guide.getVersion());
			}
			panel.add(muted(info));
		}
		panel.add(Box.createVerticalStrut(6));
		panel.add(muted("Written by So Iron BRUH and ParasailerOSRS. Stepwise follows their route "
			+ "and shows their text, but it is unofficial: the guide's authors did not make it and "
			+ "do not maintain it."));
		panel.add(Box.createVerticalStrut(2));
		panel.add(buildLink("Original guide",
			"https://docs.google.com/document/d/1CBkFM70SnrW4hJXvHM2F1fYCuBF_fRnEXnTYgRnRkAE"));
		panel.add(buildLink("Interactive guide", "https://umkyzn.github.io/BRUHsailer/"));
		panel.add(buildLink("Wiki page", "https://oldschool.runescape.wiki/w/Guide:BRUHsailer"));
	}

	/**
	 * Writes the chapter and section lines, each only when it changed. Setting a wrapping
	 * area's text re-measures it, and setToolTipText re-registers even for an unchanged value.
	 */
	void showHeading(String chapter, String section)
	{
		if (!chapter.equals(chapterText.getText()))
		{
			chapterText.setText(chapter);
			chapterText.setToolTipText(chapter.isBlank() ? null : chapter);
		}
		if (!section.equals(sectionText.getText()))
		{
			sectionText.setText(section);
			headerPanel.revalidate();
		}
	}

	/**
	 * Turns "2.1: Quests and combat" into "2.1 · Quests and combat". A title without a
	 * dotted number before its colon is returned unchanged.
	 */
	static String formatSectionLabel(String raw)
	{
		if (raw == null || raw.isEmpty())
		{
			return "";
		}
		int colon = raw.indexOf(':');
		if (colon <= 0 || raw.substring(0, colon).indexOf('.') < 0)
		{
			return raw;
		}
		String number = raw.substring(0, colon).trim();
		String name = raw.substring(colon + 1).trim();
		return name.isEmpty() ? number : number + " · " + name;
	}

	/**
	 * The guide title's first word ("BRUHsailer Complete Guide" gives "BRUHsailer"), or
	 * "Guide" when there is no title.
	 */
	private String shortGuideName()
	{
		if (guide == null || guide.getTitle() == null || guide.getTitle().isBlank())
		{
			return "Guide";
		}
		String title = guide.getTitle().trim();
		int space = title.indexOf(' ');
		return space > 0 ? title.substring(0, space) : title;
	}

	/**
	 * The guide's version as a player reads it. The BRUHsailer document stamps each version
	 * with its date as yyyyMMdd, which reads "dated" and the day, month and year; any other
	 * value reads "version" and the value.
	 */
	static String versionText(String version)
	{
		try
		{
			return "dated " + LocalDate.parse(version, DateTimeFormatter.BASIC_ISO_DATE).format(GUIDE_DATE);
		}
		catch (DateTimeParseException e)
		{
			return "version " + version;
		}
	}

	/**
	 * Formats an entry duration in minutes as "~45m" or "~1h30m", to the nearest minute and
	 * at least one. Returns null when there is no positive duration.
	 */
	static String formatEntryDuration(Double minutes)
	{
		if (minutes == null || minutes <= 0)
		{
			return null;
		}
		int mins = Math.max(1, (int) Math.round(minutes));
		if (mins < 60)
		{
			return "~" + mins + "m";
		}
		int hours = mins / 60;
		int rem = mins % 60;
		return rem > 0
			? "~" + hours + "h" + rem + "m"
			: "~" + hours + "h";
	}

	private static JPanel buildActionRow(String description, JButton button)
	{
		JPanel row = PanelSections.column();
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));

		row.add(muted(description));
		row.add(Box.createVerticalStrut(3));
		row.add(button);

		return row;
	}

	/** A Data action: it waits armed until confirmed, and does nothing without an engine. */
	private ConfirmButton dataButton(String text, Runnable action)
	{
		ConfirmButton btn = new ConfirmButton(text, false, () ->
		{
			if (coreEngine != null)
			{
				action.run();
			}
		});
		btn.setFocusPainted(false);
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.setBorderPainted(false);
		btn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		btn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return btn;
	}

	private static JTextArea buildLink(String label, String url)
	{
		JTextArea link = LinkedText.link(label, url);
		link.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		return link;
	}

	/**
	 * Collapsible "Data" section of destructive actions. Each button needs a second click
	 * to confirm, and collapsing the section cancels any pending confirmation.
	 */
	private JPanel buildResetControlsPanel()
	{
		JPanel wrapper = PanelSections.column();
		wrapper.setBorder(PanelSections.gap(PanelSections.SECTION_GAP));
		JLabel toggle = PanelSections.foldToggle("Data");

		ConfirmButton[] buttons = {
			// The reset moves the snapshot version, so the next refresh rebuilds by itself.
			dataButton("Reset Guide", () -> coreEngine.resetGuideProgress()),
			dataButton("Forget Bank", () -> coreEngine.clearBankSnapshot()),
			dataButton("Reset Bird Houses", () -> coreEngine.resetBirdhouseTimers())
		};
		String[] descriptions = {
			"Clear your guide progress. Every step the game shows as done is checked off again.",
			"Forget the bank contents Stepwise has seen. Open your bank for it to look again.",
			"Clear the bird house timers. Your guide progress is kept."
		};

		JPanel actionsPanel = PanelSections.column();
		actionsPanel.setVisible(false);

		JPanel container = PanelSections.box();
		for (int i = 0; i < buttons.length; i++)
		{
			if (i > 0)
			{
				container.add(PanelColors.divider());
			}
			container.add(buildActionRow(descriptions[i], buttons[i]));
		}
		actionsPanel.add(container);

		toggle.addMouseListener(PanelSections.clicks(() ->
		{
			boolean show = !actionsPanel.isVisible();
			actionsPanel.setVisible(show);
			toggle.setText(PanelSections.foldText("Data", show));
			if (!show)
			{
				for (ConfirmButton button : buttons)
				{
					button.disarm();
				}
			}
			wrapper.revalidate();
		}));

		wrapper.add(toggle);
		wrapper.add(actionsPanel);

		return wrapper;
	}

	/**
	 * The scroll pane's view, which stops its contents from scrolling the viewport.
	 *
	 * <p>Sections rebuild constantly while the player reads, and Swing components ask to be
	 * scrolled into view when added or focused. {@code scrollRectToVisible} propagates up the
	 * parent chain, so swallowing it here, directly below the viewport, blocks every such
	 * request. The scrollbar and mouse wheel drive the viewport directly and still work, and
	 * so does any code that sets the viewport's position itself.
	 */
	private static class ScrollAnchoredPanel extends JPanel
	{
		ScrollAnchoredPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public void scrollRectToVisible(Rectangle aRect)
		{
			// Empty on purpose. See class doc.
		}
	}

	/**
	 * A JPanel whose preferred width is {@link #CONTENT_WIDTH}, so text does not render
	 * behind the RuneLite sidebar icon strip.
	 */
	private static class FixedWidthPanel extends JPanel
	{

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(CONTENT_WIDTH, super.getPreferredSize().height);
		}
	}
}
