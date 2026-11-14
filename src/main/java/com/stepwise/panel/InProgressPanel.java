package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.DetailedStep;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Shows the steps the resolver marks {@code BACKGROUND}: long skill grinds, recurring
 * activities and crop-growth waits that run alongside the current step.
 *
 * <p>The upcoming list excludes these steps, so this section is the only place the player
 * sees them, their progress, their guidance and a Mark Done for them. The last two sit in
 * each card's Details fold (see {@link StepCardPanel#inProgress}).
 */
public class InProgressPanel extends JPanel
{
	private static final Color COLOR_OCCASION = new Color(200, 180, 120);

	/** Tracked cards for in-place criteria updates on same-structure refreshes. */
	private final List<StepCardPanel> trackedCards = new ArrayList<>();
	/** Rebuilds the list only when its structure changes. */
	private final RebuildGuard guard = new RebuildGuard();
	/**
	 * Steps whose Details fold the player opened. Folds start shut, and a structural rebuild
	 * (a step joining or leaving the list) recreates every card, so the open ones are
	 * remembered by step id and rebuilt open.
	 */
	private final Set<String> expandedStepIds = new HashSet<>();

	public InProgressPanel()
	{
		PanelSections.column(this);
	}

	public void rebuild(DashboardSnapshot snapshot, CoreEngine coreEngine, IconProvider iconProvider)
	{
		if (snapshot == null)
		{
			removeAll();
			trackedCards.clear();
			guard.reset();
			// No snapshot means logout or a reset; the next account's steps start shut.
			expandedStepIds.clear();
			setVisible(false);
			return;
		}

		List<DetailedStep> background = snapshot.getBackgroundSteps();
		if (background == null)
		{
			background = List.of();
		}

		// Progress (skill XP, contract counts) changes constantly and updates in place.
		// Keep it out of the signature, or every XP drop rebuilds the cards and they flicker.
		if (!guard.shouldRebuild(structureSignature(background)))
		{
			for (int i = 0; i < trackedCards.size(); i++)
			{
				trackedCards.get(i).updateCriteria(background.get(i).getCriteria());
			}
			return;
		}

		removeAll();
		trackedCards.clear();
		// A step that left the list will not be back open; forget it so the set stays small.
		expandedStepIds.retainAll(stepIds(background));

		if (background.isEmpty())
		{
			setVisible(false);
			revalidate();
			repaint();
			return;
		}
		setVisible(true);

		setBorder(PanelSections.gap(PanelSections.SECTION_GAP));
		add(PanelSections.header("In Progress"));

		JPanel container = PanelSections.box();

		// The snapshot arrives grouped by occasion; print one heading per group, so rows done on
		// the same trip read as one batch. Steps with no occasion form one unlabelled group.
		boolean needsDivider = false;
		String openGroup = null;
		boolean firstGroup = true;

		for (DetailedStep step : background)
		{
			String occasion = step.getWhen();
			if (firstGroup || !Objects.equals(occasion, openGroup))
			{
				if (needsDivider)
				{
					container.add(PanelColors.divider());
					needsDivider = false;
				}
				if (occasion != null)
				{
					container.add(groupHeader(occasion, firstGroup));
				}
				openGroup = occasion;
				firstGroup = false;
			}

			if (needsDivider)
			{
				container.add(PanelColors.divider());
			}

			// No per-card eyebrow: the group header already names the occasion.
			String stepId = step.getStepId();
			StepCardPanel card = StepCardPanel.inProgress(coreEngine, stepId, step.getInstruction(),
				step.getGuidance(), step.getCriteria(), step.isCompletedByClick(),
				!expandedStepIds.contains(stepId), collapsed -> rememberDetails(stepId, collapsed),
				iconProvider);
			card.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 4));
			card.setBackground(PanelColors.CONTAINER_BG);
			card.presize(getWidth());
			container.add(card);
			trackedCards.add(card);
			needsDivider = true;
		}

		add(container);

		revalidate();
		repaint();
	}

	private void rememberDetails(String stepId, boolean collapsed)
	{
		if (collapsed)
		{
			expandedStepIds.remove(stepId);
		}
		else
		{
			expandedStepIds.add(stepId);
		}
	}

	private static Set<String> stepIds(List<DetailedStep> steps)
	{
		Set<String> ids = new HashSet<>();
		for (DetailedStep step : steps)
		{
			ids.add(step.getStepId());
		}
		return ids;
	}

	/** Test seam: the cards built by the last structural rebuild, in list order. */
	List<StepCardPanel> getCardsForTest()
	{
		return trackedCards;
	}

	/**
	 * Occasion heading above a run of cards that share a {@code when}.
	 *
	 * @param first whether this is the topmost group, which needs no separating space above it
	 */
	private static JLabel groupHeader(String occasion, boolean first)
	{
		JLabel label = PanelSections.smallLabel(occasion, COLOR_OCCASION);
		label.setAlignmentX(LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(first ? 5 : 7, 6, 2, 4));
		return label;
	}

	/**
	 * Structural signature keyed by step ID and occasion. The occasion decides where group
	 * headings fall, so a changed occasion forces a rebuild even when the steps are unchanged.
	 */
	private static List<String> structureSignature(List<DetailedStep> background)
	{
		List<String> sig = new ArrayList<>();
		for (DetailedStep step : background)
		{
			sig.add("b:" + step.getStepId() + "@" + step.getWhen());
		}
		return sig;
	}
}
