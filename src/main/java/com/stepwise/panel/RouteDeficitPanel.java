package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.StepContext;
import com.stepwise.guide.StepId;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.RouteDeficit;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Shows where the player stands against the route's plan for the current entry.
 *
 * <p>Up to three stacked blocks:
 * <ol>
 *   <li><b>skills and GP</b>: every skill against the route's level, laid out like the stats
 *       tab, with the GP difference centred beneath it.</li>
 *   <li><b>quests behind</b>: the quests the route has done that the player has not, listed by
 *       name up to a limit and counted beyond it.</li>
 *   <li><b>skip advice</b>: what skipping this money-making stretch would need and cost, shown only once
 *       the player already holds the money.</li>
 * </ol>
 *
 * <p>All three sit behind a fold, shut by default, that {@link EntryBlock}'s route chip opens.
 *
 * <p>The panel only advises, except for its Skip button. That goes through
 * {@link CoreEngine#skipSteps}, the batched form of the step card's Skip button, and adds
 * nothing to the Recent section's list of clicks: the skipped steps land behind the new active
 * step, where Recent's own rows offer their Undo.
 *
 * <p>Refreshes on every snapshot. Rows are built once per structural signature and their
 * values are written in place afterwards (see {@link RebuildGuard}).
 */
public class RouteDeficitPanel extends JPanel
{
	/** Ahead of the route: the step card's met-criterion green. */
	private static final Color COLOR_AHEAD = new Color(34, 197, 94);
	/** GP behind the route. Warm rather than red, because a deficit is not a failure. */
	private static final Color COLOR_BEHIND = PanelColors.AMBER;
	private static final Color COLOR_LABEL = new Color(170, 170, 160);
	private static final Color COLOR_ADVICE = new Color(140, 190, 235);

	/** The in-game stats tab, read across its three columns row by row. */
	static final List<String> STATS_TAB_ORDER = List.of(
		"Attack", "Hitpoints", "Mining",
		"Strength", "Agility", "Smithing",
		"Defence", "Herblore", "Fishing",
		"Ranged", "Thieving", "Cooking",
		"Prayer", "Crafting", "Firemaking",
		"Magic", "Fletching", "Woodcutting",
		"Runecraft", "Slayer", "Farming",
		"Construction", "Hunter", "Sailing");
	static final int GRID_COLUMNS = 3;
	static final int GRID_HGAP = 2;
	private static final int GRID_VGAP = 4;
	/** Space above and below the grid. */
	private static final int GRID_PAD = 6;
	/** Space either side of the grid. */
	static final int GRID_SIDE = 6;
	/*
	 * Grid colours borrow the stats tab's: yellow for a level at base, green for boosted, red
	 * for drained. Here "base" is the route's level. Orange marks a skill a short way behind,
	 * so red is left for one the player has fallen well behind on. Red is deliberate here even
	 * though the GP figure avoids it: players already read the stats tab's red as "below base",
	 * not as failure.
	 */
	private static final Color COLOR_GRID_ON_ROUTE = new Color(255, 255, 0);
	private static final Color COLOR_GRID_CLOSE = new Color(255, 152, 31);
	private static final Color COLOR_GRID_BEHIND = new Color(232, 72, 56);
	/** The most levels behind that still reads as close. */
	static final int CLOSE_LEVELS = 2;

	static final int ICON_SIZE = 16;
	/** Height budgeted for the GP line and each grid row: {@link #ICON_SIZE} plus headroom. */
	private static final int ROW_HEIGHT = 20;
	private static final int QUEST_ROW_HEIGHT = 15;
	/** Quests, and separately diary tasks, listed by name; the rest are counted. */
	static final int BEHIND_ROWS_SHOWN = 8;

	private final RebuildGuard guard = new RebuildGuard();

	/** The fold's contents, shown while it is open. */
	private final JPanel container = PanelSections.box();

	/** GP row, present whenever coins are knowable; its value is written in place. */
	private JLabel gpValue;
	private JPanel gpRow;
	/** One cell per skill in {@link #STATS_TAB_ORDER}, keyed by skill name; values written in place. */
	private final Map<String, JLabel> skillCells = new HashMap<>();
	/** Skip advice, present whenever the entry opens an earn arc; shown once affordable. */
	private JPanel skipRow;

	private CoreEngine coreEngine;
	private StepwiseGuide guide;
	private IconProvider iconProvider;
	/** Entries the skip button covers, captured at build time with its label. */
	private List<String> skipArcEntryIds = List.of();
	/**
	 * The arc's Skip button, or null when the entry offers no skip. A click waits for a confirming
	 * click and disarms itself after a few seconds, because the row is not rebuilt while the
	 * entry stays the same.
	 */
	private ConfirmButton skipButton;

	private boolean gpKnown;
	/** The last GP difference written, read by the entry block's route chip. */
	private long gpDelta;
	private boolean skipOnOffer;
	private boolean skillsKnown;
	private int skillsBehind;
	private int skillsAhead;
	private boolean questsKnown;
	private int questsBehind;
	private int questsAhead;
	private int diaryTasksBehind;

	public RouteDeficitPanel()
	{
		PanelSections.column(this);
		container.setVisible(false);
		add(container);
		setVisible(false);
	}

	/**
	 * Re-render from the current snapshot.
	 *
	 * @param guide        the loaded guide, used to expand the earn arc's entries into the step
	 *                     IDs the skip action operates on; the skip button is omitted when null
	 * @param iconProvider source of the skill and coin icons; grid cells fall back to a
	 *                     three-letter abbreviation when it is null
	 */
	public void rebuild(DashboardSnapshot snapshot, CoreEngine coreEngine, StepwiseGuide guide,
		IconProvider iconProvider)
	{
		this.coreEngine = coreEngine;
		this.guide = guide;
		this.iconProvider = iconProvider;

		RouteDeficit deficit = snapshot != null ? snapshot.getRouteDeficit() : null;
		if (deficit == null || nothingToSay(deficit))
		{
			setVisible(false);
			guard.reset();
			return;
		}

		if (guard.shouldRebuild(structureSignature(deficit)))
		{
			build(deficit);
		}
		updateValues(deficit);
		setVisible(true);
	}

	private static boolean nothingToSay(RouteDeficit deficit)
	{
		return !deficit.isGpKnown()
			&& isEmpty(deficit.getSkills())
			&& isEmpty(deficit.getQuestsBehind())
			&& deficit.getQuestsAhead() == 0
			&& isEmpty(deficit.getDiaryTasksBehind())
			// Skip advice alone is enough to show the panel.
			&& deficit.getSkipAdvice() == null;
	}

	private static boolean isEmpty(List<?> list)
	{
		return list == null || list.isEmpty();
	}

	private void build(RouteDeficit deficit)
	{
		container.removeAll();
		skillCells.clear();
		gpRow = null;
		gpValue = null;
		skipRow = null;
		disarmSkip();
		skipButton = null;
		skipArcEntryIds = List.of();

		if (!isEmpty(deficit.getSkills()))
		{
			container.add(buildSkillGrid());
		}

		// Under the grid, centred, where the stats tab puts its total level.
		if (deficit.isGpKnown())
		{
			gpValue = new JLabel(" ", coinsIcon(ICON_SIZE), JLabel.CENTER);
			gpValue.setIconTextGap(4);
			gpValue.setFont(FontManager.getRunescapeSmallFont());
			gpRow = new JPanel(new BorderLayout());
			gpRow.setOpaque(false);
			gpRow.setAlignmentX(LEFT_ALIGNMENT);
			gpRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
			gpRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			gpRow.add(gpValue, BorderLayout.CENTER);
			container.add(gpRow);
		}

		if (!isEmpty(deficit.getQuestsBehind()))
		{
			if (container.getComponentCount() > 0)
			{
				container.add(PanelColors.divider());
			}
			container.add(buildQuestRows(deficit.getQuestsBehind()));
		}

		if (!isEmpty(deficit.getDiaryTasksBehind()))
		{
			if (container.getComponentCount() > 0)
			{
				container.add(PanelColors.divider());
			}
			container.add(buildDiaryRows(deficit.getDiaryTasksBehind()));
		}

		RouteDeficit.SkipAdvice advice = deficit.getSkipAdvice();
		if (advice != null)
		{
			container.add(PanelColors.divider());
			container.add(buildSkipRow(advice));
		}

		container.revalidate();
		container.repaint();
	}

	/** Writes the continuously-changing figures into rows that already exist. */
	private void updateValues(RouteDeficit deficit)
	{
		gpKnown = deficit.isGpKnown();
		if (gpRow != null)
		{
			long delta = deficit.getPlayerGp() - deficit.getRouteGp();
			gpDelta = delta;
			// The coin icon names the figure, and the word carries the direction, so no sign.
			gpValue.setText(delta == 0
				? "Level with the guide"
				: compact(Math.abs(delta)) + " " + (delta < 0 ? "behind" : "ahead"));
			gpValue.setForeground(toneOf(delta));
			gpValue.setToolTipText("You hold " + compact(deficit.getPlayerGp())
				+ " gp; the guide plans " + compact(deficit.getRouteGp()) + " gp");
		}

		if (deficit.getSkills() != null)
		{
			for (RouteDeficit.SkillDelta skill : deficit.getSkills())
			{
				JLabel cell = skillCells.get(skill.getSkill());
				if (cell == null)
				{
					continue;
				}
				int delta = skill.getDelta();
				cell.setText((cell.getIcon() == null ? abbreviation(skill.getSkill()) + " " : "")
					+ skill.getPlayerLevel() + "/" + skill.getRouteLevel());
				cell.setForeground(gridTone(delta));
				String tip = skill.getSkill() + " level " + skill.getPlayerLevel()
					+ (delta == 0 ? ", as the guide plans" : "; the guide plans " + skill.getRouteLevel());
				// Guarded: setToolTipText re-registers with the ToolTipManager even for an
				// unchanged value, and this runs for all 24 cells on every refresh.
				if (!tip.equals(cell.getToolTipText()))
				{
					cell.setToolTipText(tip);
				}
			}
		}

		questsKnown = deficit.isQuestsKnown();
		questsBehind = deficit.getQuestsBehind().size();
		questsAhead = deficit.getQuestsAhead();
		diaryTasksBehind = deficit.getDiaryTasksBehind().size();
		skillsKnown = !isEmpty(deficit.getSkills());
		skillsBehind = 0;
		skillsAhead = 0;
		if (skillsKnown)
		{
			for (RouteDeficit.SkillDelta skill : deficit.getSkills())
			{
				if (skill.getDelta() < 0)
				{
					skillsBehind++;
				}
				else if (skill.getDelta() > 0)
				{
					skillsAhead++;
				}
			}
		}

		RouteDeficit.SkipAdvice advice = deficit.getSkipAdvice();
		skipOnOffer = advice != null && advice.isAffordable();
		if (skipRow != null && advice != null)
		{
			skipRow.setVisible(advice.isAffordable());
		}
	}

	// --- Opened by the entry block's route chip ---

	/** Opens or shuts the fold. Shut, the panel takes no height. Only a change relays out. */
	void setOpen(boolean open)
	{
		if (container.isVisible() == open)
		{
			return;
		}
		container.setVisible(open);
		if (!open)
		{
			disarmSkip();
		}
		revalidate();
	}

	/** Whether there is a comparison to show. */
	boolean hasComparison()
	{
		return isVisible();
	}

	/** Whether coins have been read, so {@link #gpDelta} means something. */
	boolean isGpKnown()
	{
		return gpKnown;
	}

	/** GP held minus GP the route plans at this entry: positive is ahead. */
	long gpDelta()
	{
		return gpDelta;
	}

	/** The colour the comparison uses for {@code delta}: ahead, behind, or on route. */
	static Color toneOf(long delta)
	{
		return delta == 0 ? COLOR_LABEL : (delta < 0 ? COLOR_BEHIND : COLOR_AHEAD);
	}

	/** Whether the route profiles skills here, so the skill counts mean something. */
	boolean isSkillsKnown()
	{
		return skillsKnown;
	}

	/** Skills below the route's level. */
	int skillsBehind()
	{
		return skillsBehind;
	}

	/** Skills above the route's level. */
	int skillsAhead()
	{
		return skillsAhead;
	}

	/** Whether the quest list has been read, so the quest counts mean something. */
	boolean isQuestsKnown()
	{
		return questsKnown;
	}

	/** How many quests the guide has finished by now that the player has not. */
	int questsBehind()
	{
		return questsBehind;
	}

	/** How many quests the player has finished before the guide reaches them. */
	int questsAhead()
	{
		return questsAhead;
	}

	/** The coin stack at {@code size}, or null when there is no icon source. */
	ImageIcon coinsIcon(int size)
	{
		return iconProvider != null ? iconProvider.getCoinsIcon(size) : null;
	}

	/** The stats tab's icon, or null when there is no icon source or it has not loaded. */
	ImageIcon skillsIcon(int size)
	{
		return iconProvider != null ? iconProvider.getSkillsIcon(size) : null;
	}

	/** Whether a skip is both offered and affordable. */
	boolean isSkipOnOffer()
	{
		return skipOnOffer;
	}

	/** The skill's own tab icon, or null when there is no icon source or no such skill. */
	private ImageIcon skillIcon(String skill)
	{
		return iconProvider != null ? iconProvider.getSkillIcon(skill, ICON_SIZE) : null;
	}

	/** The quest tab's icon, or null when there is no icon source or it has not loaded. */
	ImageIcon questIcon(int size)
	{
		return iconProvider != null ? iconProvider.getQuestIcon(size) : null;
	}

	/** The diary tab's icon, or null when there is no icon source or it has not loaded. */
	ImageIcon diaryIcon(int size)
	{
		return iconProvider != null ? iconProvider.getDiaryIcon(size) : null;
	}

	/** How many diary tasks the guide asks for before this step that the player has not done. */
	int diaryTasksBehind()
	{
		return diaryTasksBehind;
	}

	/**
	 * A heading with the quest tab's icon, then one row per quest: its name, and the step
	 * that finishes it on the right. The list changes only when a quest finishes or the
	 * player passes another one, so it is built rather than written in place.
	 */
	private JPanel buildQuestRows(List<RouteDeficit.QuestBehind> quests)
	{
		JLabel heading = PanelSections.smallLabel(questsHeading(quests.size()), COLOR_BEHIND);
		heading.setIcon(questIcon(ICON_SIZE));
		heading.setIconTextGap(4);
		heading.setToolTipText("Quests the guide finishes before this step");

		List<JPanel> lines = new ArrayList<>();
		for (RouteDeficit.QuestBehind quest : quests.subList(0, Math.min(quests.size(), BEHIND_ROWS_SHOWN)))
		{
			JLabel name = PanelSections.smallLabel(
				quest.getQuestName() + (quest.isStarted() ? " (started)" : ""),
				quest.isStarted() ? COLOR_GRID_ON_ROUTE : COLOR_GRID_BEHIND);
			JPanel line = row(name, PanelSections.smallLabel(entryOf(quest.getStepId()), COLOR_LABEL),
				true);
			// Names the quest too: a long name is cut short in the row.
			line.setToolTipText(quest.getQuestName() + ", which the guide finishes in entry "
				+ entryOf(quest.getStepId()));
			lines.add(line);
		}
		return behindList(heading, lines, quests.size(), "quest", "quests");
	}

	/**
	 * A heading, then one row per diary task: the task as its step names it, and the step's
	 * entry on the right. Built like the quest rows, and for the same reason.
	 */
	private JPanel buildDiaryRows(List<RouteDeficit.DiaryTaskBehind> tasks)
	{
		JLabel heading = PanelSections.smallLabel(diaryHeading(tasks.size()), COLOR_BEHIND);
		heading.setIcon(diaryIcon(ICON_SIZE));
		heading.setIconTextGap(4);
		heading.setToolTipText("Achievement diary tasks the guide has you do before this step");

		List<JPanel> lines = new ArrayList<>();
		for (RouteDeficit.DiaryTaskBehind task : tasks.subList(0, Math.min(tasks.size(), BEHIND_ROWS_SHOWN)))
		{
			JPanel line = row(PanelSections.smallLabel(task.getTask(), COLOR_GRID_BEHIND),
				PanelSections.smallLabel(entryOf(task.getStepId()), COLOR_LABEL), true);
			// Names the task too: a long task is cut short in the row.
			line.setToolTipText(task.getTask() + ", which the guide has you do in entry "
				+ entryOf(task.getStepId()));
			lines.add(line);
		}
		return behindList(heading, lines, tasks.size(), "task", "tasks");
	}

	/**
	 * A heading over the listed rows, with a closing count of the rest. A player far behind
	 * can owe well over a hundred quests or tasks, which would push the step card off the
	 * panel. The earliest come first, so they are the ones listed.
	 */
	private static JPanel behindList(JLabel heading, List<JPanel> lines, int total, String noun,
		String nouns)
	{
		JPanel panel = PanelSections.column();
		panel.setBorder(BorderFactory.createEmptyBorder(4, 6, 5, 6));
		panel.add(row(heading, null, false));
		lines.forEach(panel::add);
		int unlisted = total - lines.size();
		if (unlisted > 0)
		{
			panel.add(row(PanelSections.smallLabel(
				unlisted + " more " + (unlisted == 1 ? noun : nouns), COLOR_LABEL), null, true));
		}
		return panel;
	}

	/** "1 diary task behind" / "3 diary tasks behind". */
	static String diaryHeading(int count)
	{
		return count + (count == 1 ? " diary task behind" : " diary tasks behind");
	}

	/**
	 * A list's heading row, or with {@code listed} set one quest's or task's own row, indented
	 * under it.
	 */
	private static JPanel row(JLabel left, JLabel right, boolean listed)
	{
		JPanel line = new JPanel(new BorderLayout(4, 0));
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, listed ? QUEST_ROW_HEIGHT : ROW_HEIGHT));
		line.setBorder(BorderFactory.createEmptyBorder(0, listed ? 4 : 0, 0, 0));
		line.add(left, BorderLayout.CENTER);
		if (right != null)
		{
			line.add(right, BorderLayout.EAST);
		}
		return line;
	}

	/** "1 quest behind" / "3 quests behind". */
	static String questsHeading(int count)
	{
		return count + (count == 1 ? " quest behind" : " quests behind");
	}

	/** The entry part of a step id: "1.1.3" for "1.1.3.1". */
	private static String entryOf(String stepId)
	{
		int dot = stepId.lastIndexOf('.');
		return dot > 0 ? stepId.substring(0, dot) : stepId;
	}

	private JPanel buildSkipRow(RouteDeficit.SkipAdvice advice)
	{
		skipArcEntryIds = advice.getArcEntryIds() != null ? advice.getArcEntryIds() : List.of();

		JPanel panel = PanelSections.column();
		panel.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 4));

		// The horizon, not the arc's closing balance: the route's spending after the earn
		// decides whether the player can skip it.
		panel.add(StepCardPanel.textArea(
			"You hold enough to skip " + arcLabel() + ". The guide spends "
				+ compact(advice.getHorizonGp()) + " gp before its next money-making stretch.",
			COLOR_ADVICE, FontManager.getRunescapeSmallFont()));

		String cost = xpLine(advice);
		if (cost != null)
		{
			JTextArea xpText = StepCardPanel.textArea(cost, COLOR_LABEL,
				FontManager.getRunescapeSmallFont());
			xpText.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
			panel.add(xpText);
		}

		panel.add(buildSkipButton());

		skipRow = panel;
		panel.setVisible(false);
		return panel;
	}

	private JPanel buildSkipButton()
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setAlignmentX(LEFT_ALIGNMENT);
		holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		holder.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		ConfirmButton skip = new ConfirmButton("Skip " + arcLabel(), true, this::skipArc);
		skip.setFont(FontManager.getRunescapeSmallFont());
		skip.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		skip.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		skip.setFocusPainted(false);
		skip.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		skipButton = skip;

		holder.add(skip, BorderLayout.WEST);
		return holder;
	}

	private void disarmSkip()
	{
		if (skipButton != null)
		{
			skipButton.disarm();
		}
	}

	/** Skips every step under the earn arc's entries, through the engine's normal skip path. */
	private void skipArc()
	{
		if (coreEngine == null || guide == null || skipArcEntryIds.isEmpty())
		{
			return;
		}
		Set<String> entries = new HashSet<>(skipArcEntryIds);
		List<StepId> steps = new ArrayList<>();
		for (GuideStep step : guide.allSteps())
		{
			StepContext ctx = guide.stepContextMap().get(step.getId());
			if (ctx != null && entries.contains(ctx.getEntryId()))
			{
				steps.add(step.getId());
			}
		}
		coreEngine.skipSteps(steps);
	}

	/** "entry 2.1.1" for a single-entry arc, "entries 2.1.1 to 2.1.2" when it spans several. */
	private String arcLabel()
	{
		if (skipArcEntryIds.isEmpty())
		{
			return "this money-making stretch";
		}
		String first = skipArcEntryIds.get(0);
		String last = skipArcEntryIds.get(skipArcEntryIds.size() - 1);
		return first.equals(last) ? "entry " + first : "entries " + first + " to " + last;
	}

	/** "Gives up 11,250 Crafting XP, 45,000 Magic XP.", or null when the arc trains nothing. */
	private static String xpLine(RouteDeficit.SkipAdvice advice)
	{
		if (isEmpty(advice.getXpCarried()))
		{
			return null;
		}
		StringBuilder sb = new StringBuilder("Gives up ");
		for (int i = 0; i < advice.getXpCarried().size(); i++)
		{
			if (i > 0)
			{
				sb.append(", ");
			}
			RouteDeficit.XpAward award = advice.getXpCarried().get(i);
			sb.append(String.format(Locale.ROOT, "%,d ", award.getXp()))
				.append(award.getSkill()).append(" XP");
		}
		return sb.append('.').toString();
	}

	/**
	 * Every skill in the stats tab's own layout, so a player finds each one where the game puts
	 * it. On-route skills keep their cell rather than leaving a hole in the grid.
	 */
	private JPanel buildSkillGrid()
	{
		int rows = (STATS_TAB_ORDER.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
		JPanel grid = new JPanel(new GridLayout(rows, GRID_COLUMNS, GRID_HGAP, GRID_VGAP));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			rows * ROW_HEIGHT + (rows - 1) * GRID_VGAP + 2 * GRID_PAD));
		grid.setBorder(BorderFactory.createEmptyBorder(GRID_PAD, GRID_SIDE, GRID_PAD, GRID_SIDE));
		for (String skill : STATS_TAB_ORDER)
		{
			ImageIcon icon = skillIcon(skill);
			JLabel cell = new JLabel(" ", icon, JLabel.CENTER);
			cell.setIconTextGap(4);
			cell.setFont(FontManager.getRunescapeSmallFont());
			cell.setForeground(COLOR_LABEL);
			if (icon == null)
			{
				cell.setText(abbreviation(skill));
			}
			skillCells.put(skill, cell);
			grid.add(cell);
		}
		return grid;
	}

	/** A grid cell's colour for a skill {@code delta} levels off the route. */
	static Color gridTone(int delta)
	{
		if (delta > 0)
		{
			return COLOR_AHEAD;
		}
		if (delta == 0)
		{
			return COLOR_GRID_ON_ROUTE;
		}
		return -delta <= CLOSE_LEVELS ? COLOR_GRID_CLOSE : COLOR_GRID_BEHIND;
	}

	/** Names a cell's skill when there is no sprite to do it. */
	private static String abbreviation(String skill)
	{
		return skill.substring(0, 3);
	}

	/**
	 * Structural inputs only: which entry, whether coins are readable, whether the skill grid
	 * is shown, whether an earn arc is on offer, and the quest and diary task rows.
	 *
	 * <p>Excludes every displayed figure, which is written in place. The grid's cells are fixed,
	 * so a level-up never changes its structure.
	 */
	private List<String> structureSignature(RouteDeficit deficit)
	{
		List<String> sig = new ArrayList<>();
		sig.add("e:" + deficit.getEntryId());
		sig.add("gp:" + deficit.isGpKnown());
		sig.add("grid:" + !isEmpty(deficit.getSkills()));
		RouteDeficit.SkipAdvice advice = deficit.getSkipAdvice();
		sig.add("k:" + (advice != null ? advice.getArcEntryIds() + "@" + advice.getHorizonGp() : "none"));
		// The listed quests by value, so a finished one drops its row and a started one
		// changes its label. The rest show only as a count.
		List<RouteDeficit.QuestBehind> quests = deficit.getQuestsBehind();
		sig.add("q:" + quests.size() + ":"
			+ quests.subList(0, Math.min(quests.size(), BEHIND_ROWS_SHOWN)));
		// The heading bakes in the quest tab's icon, which loads after the first snapshots.
		sig.add("qi:" + (!quests.isEmpty() && questIcon(ICON_SIZE) != null));
		// The listed diary tasks by value, so a finished one drops its row.
		List<RouteDeficit.DiaryTaskBehind> tasks = deficit.getDiaryTasksBehind();
		sig.add("d:" + tasks.size() + ":" + tasks.subList(0, Math.min(tasks.size(), BEHIND_ROWS_SHOWN)));
		sig.add("di:" + (!tasks.isEmpty() && diaryIcon(ICON_SIZE) != null));
		return sig;
	}

	/** "+3.9M" / "-6" — a signed figure, with the sign always shown. */
	static String signed(long value)
	{
		return (value < 0 ? "-" : "+") + compact(Math.abs(value));
	}

	/**
	 * Amounts at panel width: 6,693, 149k, 3.9M. Rounded to the figure shown, and an amount
	 * that would round to 1000k reads 1M instead.
	 */
	static String compact(long value)
	{
		if (value < 10_000)
		{
			return String.format(Locale.ROOT, "%,d", value);
		}
		if (value < 999_500)
		{
			return rounded(value / 1_000.0) + "k";
		}
		return rounded(value / 1_000_000.0) + "M";
	}

	/** One decimal below 100, none from there up, and no trailing ".0". */
	private static String rounded(double scaled)
	{
		String text = scaled >= 99.95
			? String.valueOf(Math.round(scaled))
			: String.format(Locale.ROOT, "%.1f", scaled);
		return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
	}
}
