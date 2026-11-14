package com.stepwise.panel;

import com.stepwise.engine.BirdhouseTracker;
import com.stepwise.engine.CoreEngine;
import com.stepwise.engine.KingdomTracker;
import com.stepwise.engine.TearsOfGuthixTracker;
import com.stepwise.model.PlayerState;
import com.stepwise.snapshot.DashboardSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.FontManager;

/**
 * "Timers" section: one collapsed row for bird houses, the weekly Tears of Guthix row, and
 * the Miscellania row.
 *
 * <p>The bird house row shares the countdown and colour of Stepwise's bird house InfoBox. Only
 * the InfoBox's tooltip names each house's tier. The countdown is the longest remaining of the
 * four houses, because the player goes when the whole run is ready. The colour walks green
 * (none ready), yellow-green (some ready), gold (all ready).
 *
 * <p>No var carries the Tears of Guthix wait, so
 * {@link com.stepwise.engine.TearsOfGuthixTracker} reads it from what the player sees: a
 * visit, Juna's lines and the Quest Journal page. Juna's second gate, new adventures since
 * the last visit, can keep her shut after the clock reads ready, so while it stands it takes
 * the row's second line and colour.
 */
public class TimerPanel extends JPanel
{
	private static final Color COLOR_HEADER = PanelColors.HEADER;
	private static final Color COLOR_READY = new Color(255, 200, 50);
	private static final Color COLOR_GROWING = new Color(100, 160, 100);
	private static final Color COLOR_PARTIAL = new Color(180, 200, 90);
	private static final Color COLOR_SUBTEXT = new Color(140, 140, 140);

	private static final String TEARS_NAME = "Tears of Guthix";

	/** Juna's stone bowl, the item the minigame hands you. */
	private static final int TEARS_ICON = ItemID.TOG_BOWL;

	private static final String TEARS_READY = "Ready";

	private static final String TEARS_UNKNOWN = "Check journal";

	/** Shown when the week is up but Juna is still owed new adventures. */
	private static final String TEARS_ADVENTURES = "Needs XP";

	private static final String KINGDOM_DUE = "Visit";

	/** Thirty days without a visit: the kingdom has stopped until the player returns. */
	private static final String KINGDOM_PAUSED = "Paused";

	private static final DateTimeFormatter TIME_FMT =
		DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

	public TimerPanel()
	{
		PanelSections.column(this);
	}

	public void rebuild(DashboardSnapshot snapshot, CoreEngine coreEngine, IconProvider iconProvider)
	{
		removeAll();

		List<Row> rows = coreEngine == null ? new ArrayList<>() : collectRows(snapshot, coreEngine);
		if (rows.isEmpty())
		{
			setVisible(false);
			revalidate();
			repaint();
			return;
		}

		setVisible(true);
		setBorder(PanelSections.gap(PanelSections.SECTION_GAP));
		add(PanelSections.header("Timers"));

		JPanel container = PanelSections.box();

		for (int i = 0; i < rows.size(); i++)
		{
			if (i > 0)
			{
				container.add(PanelColors.divider());
			}
			container.add(buildTimerRow(rows.get(i), iconProvider));
		}
		add(container);

		revalidate();
		repaint();
	}

	private List<Row> collectRows(DashboardSnapshot snapshot, CoreEngine coreEngine)
	{
		List<Row> rows = new ArrayList<>();
		long nowSec = Instant.now().getEpochSecond();

		// Errands sort first: Tears of Guthix when it is a trip worth making, then a due
		// Miscellania. Bird houses follow, then whichever of the other two is still waiting.
		// Read once here, because the client thread can stamp a visit mid-pass, and two reads
		// could render the row twice or not at all.
		PlayerState player = coreEngine.getPlayerState();
		TearsOfGuthixTracker.Reading tears =
			TearsOfGuthixTracker.questCompleted(player)
				? coreEngine.getTearsOfGuthixTracker().readAt(nowSec,
					player.getTotalXp(), player.getQuestPoints())
				: null;

		if (tears != null && tearsIsAnErrand(tears))
		{
			rows.add(tearsOfGuthixRow(tears));
		}

		// Read once for the same reason as Tears of Guthix.
		KingdomTracker.Reading kingdom =
			KingdomTracker.questCompleted(player)
				? coreEngine.getKingdomTracker().readAt(nowSec,
					KingdomTracker.royalTroubleCompleted(player))
				: null;
		String hardwoodAdvice = kingdom != null && snapshot != null
			? coreEngine.getKingdomTracker().hardwoodAdvice(snapshot.getKingdomHardwood(),
				KingdomTracker.royalTroubleCompleted(player))
			: null;

		List<KingdomTracker.Yield> kingdomEstimate = kingdom != null
			? coreEngine.getKingdomTracker().collectionEstimate(kingdom.getPool())
			: null;

		if (kingdom != null && kingdom.isDue())
		{
			rows.add(kingdomRow(kingdom, hardwoodAdvice, kingdomEstimate));
		}

		Row birdhouseRow = birdhouseClassRow(coreEngine.getBirdhouseTracker(), nowSec);
		if (birdhouseRow != null)
		{
			rows.add(birdhouseRow);
		}

		if (tears != null && !tearsIsAnErrand(tears))
		{
			rows.add(tearsOfGuthixRow(tears));
		}

		if (kingdom != null && !kingdom.isDue())
		{
			rows.add(kingdomRow(kingdom, hardwoodAdvice, kingdomEstimate));
		}

		return rows;
	}

	/**
	 * The Kingdom of Miscellania row. Never null.
	 *
	 * <p>Gold once the trip is due or the kingdom has paused. Approval and coffer take the second line because together
	 * they set the daily yield, so the player sees what is slipping before the row turns gold.
	 * Advice about the hardwood workers takes that line instead while it stands: a wrong setting
	 * at collection costs the whole pool, and the fix is one click at the advisor.
	 *
	 * <p>The tooltip estimates what collecting now would give, and keeps approval and coffer
	 * readable while advice holds the second line.
	 *
	 * @param hardwoodAdvice what to change on the hardwood workers, or null when nothing needs
	 *                       changing
	 * @param estimate       what collecting now would give, possibly empty
	 */
	private Row kingdomRow(KingdomTracker.Reading kingdom, String hardwoodAdvice,
		List<KingdomTracker.Yield> estimate)
	{
		String summary = kingdomSummary(kingdom.getApprovalPercent(), kingdom.getCoffer(),
			kingdom.isCofferLow());
		String status;
		Color color;
		if (kingdom.isPaused())
		{
			status = KINGDOM_PAUSED;
			color = COLOR_READY;
		}
		else if (kingdom.isDue())
		{
			status = KINGDOM_DUE;
			color = COLOR_READY;
		}
		else
		{
			status = daysStatus(kingdom.getDaysUntilDue());
			color = COLOR_GROWING;
		}
		String subtext = hardwoodAdvice != null ? hardwoodAdvice : summary;
		String displaced = hardwoodAdvice != null ? summary : null;
		// Mahogany logs, what the guide keeps the kingdom producing.
		return new Row("Miscellania", ItemID.MAHOGANY_LOGS, status, color, subtext, null)
			.withTooltip(kingdomTooltip(displaced, estimate));
	}

	/**
	 * The row's tooltip: the summary when advice displaced it, then what collecting now would give.
	 * Null when there is nothing to say.
	 */
	static String kingdomTooltip(String displacedSummary, List<KingdomTracker.Yield> estimate)
	{
		boolean hasEstimate = estimate != null && !estimate.isEmpty();
		if (displacedSummary == null && !hasEstimate)
		{
			return null;
		}
		StringBuilder tip = new StringBuilder("<html>");
		if (displacedSummary != null)
		{
			tip.append(displacedSummary);
		}
		if (hasEstimate)
		{
			if (displacedSummary != null)
			{
				tip.append("<br>");
			}
			tip.append("Collecting now gives about:");
			for (KingdomTracker.Yield item : estimate)
			{
				tip.append("<br>").append(String.format(Locale.ROOT, "%,d", item.getQuantity()))
					.append(' ').append(item.getItem());
			}
		}
		return tip.append("</html>").toString();
	}

	/**
	 * "Approval 96% · 6.2M gp", the coffer's balance, with ", low" once it can no longer pay
	 * full wages. The line does not wrap, and "coffer" would push the longest reading past
	 * the row's width.
	 */
	static String kingdomSummary(int approvalPercent, int coffer, boolean cofferLow)
	{
		return "Approval " + approvalPercent + "% · " + compactGp(coffer) + " gp"
			+ (cofferLow ? ", low" : "");
	}

	/** Coins rounded down: 6_250_000 → "6.2M", 400_000 → "400k". Rounding down never flatters. */
	static String compactGp(int gp)
	{
		if (gp >= 1_000_000)
		{
			int tenths = gp / 100_000;
			return (tenths / 10) + (tenths % 10 == 0 ? "" : "." + (tenths % 10)) + "M";
		}
		return gp >= 1_000 ? (gp / 1_000) + "k" : Integer.toString(gp);
	}

	/**
	 * Whether the Tears of Guthix row is a trip the player can make right now, which decides
	 * where it sorts. Both insertion points use this one predicate so the row lands exactly once.
	 *
	 * <p>A finished clock with Juna's adventures gate still standing is not an errand: she
	 * would turn the player away.
	 */
	static boolean tearsIsAnErrand(TearsOfGuthixTracker.Reading tears)
	{
		return tears.isReady() && !tears.isAdventuresNeeded();
	}

	/**
	 * The weekly Tears of Guthix row. Never null.
	 *
	 * <p>No "mark done" action, because the visit is observed rather than declared. Until the
	 * tracker has a reading the row points at the Quest Journal, which states the exact wait.
	 *
	 * <p>A wait in whole days has no honest clock time, so the row has a second line only
	 * for the XP or quest point Juna is still owed.
	 */
	private Row tearsOfGuthixRow(TearsOfGuthixTracker.Reading tears)
	{
		String adventures = tears.isAdventuresNeeded() ? tearsAdventures(tears.getXpToGo()) : null;

		if (tears.isReady())
		{
			// Ready but gated must not be gold, or the player walks to the Chasm of Tears
			// for nothing. Grey marks "nothing to do here".
			return adventures == null
				? new Row(TEARS_NAME, TEARS_ICON, TEARS_READY, COLOR_READY, null, null)
				: new Row(TEARS_NAME, TEARS_ICON, TEARS_ADVENTURES, COLOR_SUBTEXT, adventures, null);
		}

		// An unread clock can still carry a gate, because Juna's gate message names no day
		// count. The row then shows both.
		if (tears.isUnknown())
		{
			return new Row(TEARS_NAME, TEARS_ICON, TEARS_UNKNOWN, COLOR_SUBTEXT, adventures, null);
		}

		return new Row(TEARS_NAME, TEARS_ICON, daysStatus(tears.getDaysRemaining()), COLOR_GROWING,
			adventures, tears.getProgress());
	}

	/**
	 * What Juna is still owed, as the row's second line. Names both options, since a quest
	 * point is often cheaper. The line does not wrap, so it is short enough for the row.
	 */
	static String tearsAdventures(long xpToGo)
	{
		return xpToGo <= 0
			? "1 quest point to go"
			: String.format(Locale.ROOT, "%,d XP or 1 quest point", xpToGo);
	}

	/**
	 * The countdown label for a day-valued wait: "3d", with no hours, because the wait
	 * moves only once a day.
	 */
	static String daysStatus(int daysRemaining)
	{
		return daysRemaining + "d";
	}

	/** One collapsed row for all seeded bird houses, or null when none are seeded. */
	static Row birdhouseClassRow(BirdhouseTracker birdhouses, long nowSec)
	{
		int seeded = 0;
		int ready = 0;
		long longestRemaining = 0;
		long latestReadyAt = 0;
		StringBuilder tip = new StringBuilder("<html>Bird houses");

		for (BirdhouseTracker.BirdhouseSpace space : BirdhouseTracker.BirdhouseSpace.values())
		{
			if (!birdhouses.isSeeded(space))
			{
				continue;
			}
			seeded++;
			long remaining = birdhouses.remainingSeconds(space, nowSec);
			long readyAt = birdhouses.readyAtEpochSeconds(space);
			if (remaining <= 0)
			{
				ready++;
			}
			if (remaining > longestRemaining)
			{
				longestRemaining = remaining;
			}
			if (readyAt > latestReadyAt)
			{
				latestReadyAt = readyAt;
			}
			tip.append("<br>").append(space.getLabel()).append(": ")
				.append(BirdhouseTracker.timeLeft(remaining));
		}

		if (seeded == 0)
		{
			return null;
		}
		tip.append("</html>");

		String counts = ready + "/" + seeded + " ready";
		String status = longestRemaining <= 0 ? "Ready" : ((longestRemaining + 59) / 60) + "m";
		Color color = longestRemaining <= 0 ? COLOR_READY
			: (ready > 0 ? COLOR_PARTIAL : COLOR_GROWING);
		// The finish time only while it is still ahead: once every house is ready it has passed.
		String doneAt = longestRemaining > 0
			? counts + " · all ready at " + TIME_FMT.format(Instant.ofEpochSecond(latestReadyAt))
			: counts;
		// The bar clamps a wait longer than one fill to empty.
		double progress = 1.0 - (double) longestRemaining / BirdhouseTracker.FILL_DURATION_SECONDS;
		// The plain (logs) bird house.
		return new Row("Bird houses", ItemID.BIRDHOUSE_NORMAL, status, color, doneAt, progress)
			.withTooltip(tip.toString());
	}

	private JPanel buildTimerRow(Row row, IconProvider iconProvider)
	{
		JPanel outer = new JPanel(new BorderLayout(6, 3));
		outer.setOpaque(false);
		outer.setAlignmentX(LEFT_ALIGNMENT);
		outer.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		ImageIcon icon = iconProvider != null
			? iconProvider.getItemIcon(row.itemId, IconProvider.TIMER_ICON_SIZE) : null;
		if (icon != null)
		{
			outer.add(new JLabel(icon), BorderLayout.WEST);
		}

		JPanel textBlock = new JPanel(new GridLayout(row.subtext != null ? 2 : 1, 1, 0, 0));
		textBlock.setOpaque(false);

		JPanel line1 = new JPanel(new BorderLayout(6, 0));
		line1.setOpaque(false);
		JTextArea nameText = StepCardPanel.textArea(row.name,
			COLOR_HEADER, FontManager.getRunescapeSmallFont());
		nameText.setLineWrap(false);
		line1.add(nameText, BorderLayout.CENTER);
		JTextArea statusText = StepCardPanel.textArea(row.status,
			row.color, FontManager.getRunescapeSmallFont());
		statusText.setLineWrap(false);
		line1.add(statusText, BorderLayout.EAST);
		textBlock.add(line1);

		JTextArea subText = null;
		if (row.subtext != null)
		{
			subText = StepCardPanel.textArea(row.subtext,
				COLOR_SUBTEXT, FontManager.getRunescapeSmallFont());
			subText.setLineWrap(false);
			textBlock.add(subText);
		}

		// The tooltip goes on the container AND the text areas: JTextArea children swallow
		// mouse events, so setting it only on the outer panel would leave dead zones over
		// the text.
		if (row.tooltip != null)
		{
			for (JComponent part : new JComponent[]{outer, nameText, statusText, subText})
			{
				if (part != null)
				{
					part.setToolTipText(row.tooltip);
				}
			}
		}

		outer.add(textBlock, BorderLayout.CENTER);

		boolean bar = showsBar(row.progress);
		if (bar)
		{
			outer.add(new TimerProgressBar(row.progress), BorderLayout.SOUTH);
		}

		// BoxLayout enforces this maximum to the pixel, so height must follow the line count
		// and the bar independently or a two-line row without a bar clips its second line.
		int textHeight = row.subtext != null ? 40 : 26;
		outer.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			textHeight + (bar ? 8 : 0)));
		return outer;
	}

	/** Whether a row draws its progress bar: only while filling, since "Ready" says the rest. */
	static boolean showsBar(Double progress)
	{
		return progress != null && progress < 1.0;
	}

	/**
	 * One timer row, headed by an item's sprite. Subtext is the optional second line. Progress
	 * is set only for rows with a fill duration.
	 */
	static final class Row
	{
		final String name;
		final int itemId;
		final String status;
		final Color color;
		final String subtext;
		final Double progress;
		/** Optional, HTML. */
		String tooltip;

		Row(String name, int itemId, String status, Color color, String subtext, Double progress)
		{
			this.name = name;
			this.itemId = itemId;
			this.status = status;
			this.color = color;
			this.subtext = subtext;
			this.progress = progress;
		}

		Row withTooltip(String tooltip)
		{
			this.tooltip = tooltip;
			return this;
		}
	}
}
