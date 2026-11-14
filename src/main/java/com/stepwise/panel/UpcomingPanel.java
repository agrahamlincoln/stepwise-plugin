package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.snapshot.CompletionCriterion;
import com.stepwise.snapshot.CriterionProgress;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.DetailedStep;
import com.stepwise.snapshot.NextStep;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.client.ui.FontManager;

/**
 * Shows upcoming steps as compact rows: icons, instruction, and a progress count when the
 * step has one. The icons and the count come from the detailed steps' criteria.
 */
public class UpcomingPanel extends JPanel
{
	private static final Color COLOR_ONE_LINER = PanelColors.HEADER;
	private static final Color COLOR_SECTION = PanelColors.SUBTLE.brighter();

	/** Icons drawn per row. More squeeze the instruction at the sidebar's width. */
	private static final int MAX_ICONS = 2;

	/** The active step's section. Rows in the same section omit their breadcrumb. */
	private String currentSection;

	/**
	 * The progress counts on screen, one per detailed step, null where the step has none.
	 * Updated in place on a same-structure refresh, so an XP drop does not rebuild the
	 * rows and re-wrap their text.
	 */
	private final List<JLabel> progressLabels = new ArrayList<>();
	/** Rebuilds the upcoming list only when its structure changes. */
	private final RebuildGuard guard = new RebuildGuard();

	public UpcomingPanel()
	{
		PanelSections.column(this);
	}

	public void rebuild(DashboardSnapshot snapshot, CoreEngine coreEngine, IconProvider iconProvider)
	{
		if (snapshot == null)
		{
			removeAll();
			progressLabels.clear();
			guard.reset();
			setVisible(false);
			return;
		}

		if (snapshot.getGuidePosition() != null)
		{
			currentSection = snapshot.getGuidePosition().getSection();
		}

		List<DetailedStep> detailed = snapshot.getNextDetailed();
		if (detailed == null)
		{
			detailed = List.of();
		}

		List<NextStep> oneLiners = snapshot.getNextSteps();

		if (!guard.shouldRebuild(structureSignature(detailed, oneLiners)))
		{
			for (int i = 0; i < progressLabels.size() && i < detailed.size(); i++)
			{
				JLabel label = progressLabels.get(i);
				if (label != null)
				{
					label.setText(progressText(detailed.get(i).getCriteria()));
				}
			}
			return;
		}

		removeAll();
		progressLabels.clear();

		boolean hasDetailed = !detailed.isEmpty();
		boolean hasOneLiners = oneLiners != null && !oneLiners.isEmpty();

		if (!hasDetailed && !hasOneLiners)
		{
			setVisible(false);
			revalidate();
			repaint();
			return;
		}

		// Visibility is set only on a rebuild: an unchanged snapshot keeps whatever the last
		// rebuild decided, so an empty list is not shown again.
		setVisible(true);
		setBorder(PanelSections.gap(PanelSections.SECTION_GAP));
		add(PanelSections.header("Upcoming"));

		JPanel container = PanelSections.box();

		boolean needsDivider = false;

		for (DetailedStep step : detailed)
		{
			if (needsDivider)
			{
				container.add(PanelColors.divider());
			}

			String section = step.getSection();
			if (section != null && section.equals(currentSection))
			{
				section = null;
			}
			else if (section != null)
			{
				// Written as the header writes the current section, "2.2 · Name".
				section = StepwisePanel.formatSectionLabel(TextMarkup.stripMarkup(section));
			}

			String progress = progressText(step.getCriteria());
			JLabel progressLabel = progress.isEmpty() ? null : progressLabel(progress);
			progressLabels.add(progressLabel);
			container.add(buildRow(section, iconsOf(step.getCriteria(), iconProvider),
				step.getInstruction(), progressLabel));
			needsDivider = true;
		}

		if (hasOneLiners)
		{
			for (NextStep step : oneLiners)
			{
				if (needsDivider)
				{
					container.add(PanelColors.divider());
				}
				container.add(buildRow(null, iconsOf(step, iconProvider),
					step.getInstruction(), null));
				needsDivider = true;
			}
		}

		add(container);

		revalidate();
		repaint();
	}

	/**
	 * Structural signature for the upcoming list. Detailed rows are keyed by step ID and
	 * the shape of their progress count, not its value. One-liners are keyed by their text.
	 */
	private static List<String> structureSignature(List<DetailedStep> detailed,
		List<NextStep> oneLiners)
	{
		List<String> sig = new ArrayList<>();
		for (DetailedStep step : detailed)
		{
			// The count's source criterion and length set the label's width, which wraps the
			// instruction beside it. A change to either needs a rebuild. Same-length values
			// update in place.
			sig.add("d:" + step.getStepId() + ":" + progressSource(step.getCriteria())
				+ ":" + progressText(step.getCriteria()).length());
		}
		if (oneLiners != null)
		{
			for (NextStep step : oneLiners)
			{
				sig.add("o:" + step.getInstruction());
			}
		}
		return sig;
	}

	/**
	 * How far along a step is, from its first criterion that is still counting: a level
	 * for a skill, a count for anything else. Empty when nothing on the step counts.
	 */
	static String progressText(List<CompletionCriterion> criteria)
	{
		int source = progressSource(criteria);
		if (source < 0)
		{
			return "";
		}
		CriterionProgress p = criteria.get(source).getProgress();
		// Show a skill in levels until the level is reached. After that an XP target
		// remains, and "50/50" would read as done.
		if (p.getTargetLevel() > 0 && p.getCurrentLevel() < p.getTargetLevel())
		{
			return p.getCurrentLevel() + "/" + p.getTargetLevel();
		}
		// Abbreviated so a large gold or item count does not squeeze the instruction.
		return RouteDeficitPanel.compact(p.getCurrent()) + "/" + RouteDeficitPanel.compact(p.getRequired());
	}

	/** The first criterion still counting towards something, or -1 when none is. */
	private static int progressSource(List<CompletionCriterion> criteria)
	{
		if (criteria == null)
		{
			return -1;
		}
		for (int i = 0; i < criteria.size(); i++)
		{
			if (StepCardPanel.isCounting(criteria.get(i)))
			{
				return i;
			}
		}
		return -1;
	}

	private static JLabel progressLabel(String text)
	{
		JLabel label = PanelSections.smallLabel(text, COLOR_ONE_LINER);
		label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
		return label;
	}

	/** One icon per distinct criterion picture, at most {@link #MAX_ICONS}. */
	private static List<ImageIcon> iconsOf(List<CompletionCriterion> criteria,
		IconProvider iconProvider)
	{
		List<String[]> live = new ArrayList<>();
		List<String[]> all = new ArrayList<>();
		if (criteria != null)
		{
			for (CompletionCriterion criterion : criteria)
			{
				String[] hint = {criterion.getIconType(), criterion.getIconKey()};
				all.add(hint);
				if (!criterion.isMet() && !criterion.isWaived())
				{
					live.add(hint);
				}
			}
		}
		// Prefer unmet criteria. Fall back to all of them when every one is met.
		return icons(live.isEmpty() ? all : live, iconProvider);
	}

	private static List<ImageIcon> iconsOf(NextStep step, IconProvider iconProvider)
	{
		List<String[]> hints = new ArrayList<>();
		if (step.getIconHints() != null)
		{
			for (NextStep.IconHint hint : step.getIconHints())
			{
				hints.add(new String[]{hint.getIconType(), hint.getIconKey()});
			}
		}
		return icons(hints, iconProvider);
	}

	private static List<ImageIcon> icons(List<String[]> hints, IconProvider iconProvider)
	{
		List<ImageIcon> icons = new ArrayList<>();
		if (iconProvider == null)
		{
			return icons;
		}
		// Dedupe by icon type and key, so three criteria on one item draw one icon.
		Set<String> seen = new LinkedHashSet<>();
		for (String[] hint : hints)
		{
			if (hint[0] == null || !seen.add(hint[0] + ":" + hint[1]))
			{
				continue;
			}
			ImageIcon icon = iconProvider.getIcon(hint[0], hint[1], false);
			if (icon != null)
			{
				icons.add(icon);
				if (icons.size() == MAX_ICONS)
				{
					break;
				}
			}
		}
		return icons;
	}

	private static JPanel buildRow(String section, List<ImageIcon> icons, String instruction,
		JLabel progress)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
		row.setAlignmentX(LEFT_ALIGNMENT);

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.CENTER;

		if (section != null)
		{
			JTextArea eyebrow = StepCardPanel.textArea(section, COLOR_SECTION,
				FontManager.getRunescapeSmallFont());
			gbc.gridx = 0;
			gbc.gridwidth = GridBagConstraints.REMAINDER;
			gbc.weightx = 1.0;
			gbc.fill = GridBagConstraints.HORIZONTAL;
			row.add(eyebrow, gbc);
			gbc.gridy = 1;
			gbc.gridwidth = 1;
		}

		int col = 0;
		for (ImageIcon icon : icons)
		{
			JLabel iconLabel = new JLabel(icon);
			iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 3));
			gbc.gridx = col++;
			gbc.weightx = 0;
			gbc.fill = GridBagConstraints.NONE;
			row.add(iconLabel, gbc);
		}

		JTextArea instrText = StepCardPanel.textArea(
			instruction != null ? instruction : "", COLOR_ONE_LINER,
			FontManager.getRunescapeSmallFont());
		gbc.gridx = col++;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		row.add(instrText, gbc);

		if (progress != null)
		{
			gbc.gridx = col;
			gbc.weightx = 0;
			gbc.fill = GridBagConstraints.NONE;
			row.add(progress, gbc);
		}

		return row;
	}
}
