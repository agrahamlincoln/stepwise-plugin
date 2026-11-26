package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepId;
import com.stepwise.guide.StepwiseGuide;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * The preview controls. A one-line banner above the entry names the player's real entry and
 * offers a way back. The card itself carries the paging arrows, in the same corner whether it
 * shows the player's own step or a preview, so a player can keep clicking without chasing them.
 * A previewed step behind the player's own also offers Move here in Mark Done's place.
 */
final class PreviewBar
{
	static final Color BANNER_BG = new Color(48, 56, 72);
	private static final Color COLOR_BANNER_TEXT = new Color(200, 210, 230);
	/** A previewed card's accent, in place of the current step's orange. */
	static final Color ACCENT = new Color(110, 140, 200);
	/** Each arrow's width: two fit beside Mark Done and Skip. */
	private static final int ARROW_WIDTH = 18;

	private PreviewBar()
	{
	}

	/**
	 * @param currentStepId the player's own step, which the banner names
	 * @param preview       shows a step, or ends the preview with null
	 */
	static JPanel banner(String currentStepId, Consumer<StepId> preview)
	{
		JPanel banner = new JPanel(new BorderLayout(4, 0));
		banner.setOpaque(true);
		banner.setBackground(BANNER_BG);
		banner.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		banner.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		String where = currentStepId != null
			? " · you're on entry " + currentStepId.substring(0, Math.max(0, currentStepId.lastIndexOf('.')))
			: "";
		banner.add(PanelSections.smallLabel("Preview" + where, COLOR_BANNER_TEXT), BorderLayout.CENTER);
		banner.add(PanelSections.link("Back", PanelColors.LINK, "Back to your current step",
			() -> preview.accept(null)), BorderLayout.EAST);
		banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height));
		return banner;
	}

	/**
	 * Move here for a previewed step behind the player's own, which makes it their current
	 * step and ends the preview ({@link CoreEngine#moveToStep}); null for any other step, or
	 * with no engine.
	 *
	 * @param engine the logged-in account's engine. The welcome page's read-only account never
	 *               calls this, since nothing done while logged out is saved
	 */
	static ConfirmButton moveHere(String previewedStepId, String currentStepId, StepwiseGuide guide,
		CoreEngine engine)
	{
		Map<String, Integer> positions = guide.stepPositionIndex();
		int at = positions.getOrDefault(previewedStepId, -1);
		int current = positions.getOrDefault(currentStepId, -1);
		if (engine == null || at < 0 || current < 0 || at >= current)
		{
			return null;
		}
		ConfirmButton move = StepCardPanel.smallButton(new ConfirmButton("Move here", true,
			() -> engine.moveToStep(new StepId(previewedStepId))), 76);
		move.setToolTipText("Make this your current step");
		return move;
	}

	/**
	 * A previous and a next button that preview the steps either side of {@code stepId}, so
	 * a player can look at the steps before or after it, anywhere in the guide, without
	 * marking anything.
	 */
	static JPanel paging(String stepId, StepwiseGuide guide, Consumer<StepId> preview)
	{
		JPanel paging = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		paging.setOpaque(false);
		paging.add(stepButton("<", "Preview the previous step",
			neighbour(guide, stepId, -1), preview));
		paging.add(Box.createHorizontalStrut(4));
		paging.add(stepButton(">", "Preview the next step",
			neighbour(guide, stepId, 1), preview));
		return paging;
	}

	private static JButton stepButton(String text, String tooltip, String target,
		Consumer<StepId> preview)
	{
		JButton button = StepCardPanel.smallButton(new JButton(text), ARROW_WIDTH);
		button.setToolTipText(tooltip);
		button.setEnabled(target != null);
		if (target != null)
		{
			button.addActionListener(e -> preview.accept(new StepId(target)));
		}
		return button;
	}

	/**
	 * The nearest step to {@code stepId} in {@code direction} that is not a background step,
	 * or null at either end of the guide or for an unknown step. Background steps are skipped
	 * because they never hold the current slot, so previewing one shows nothing to do.
	 */
	private static String neighbour(StepwiseGuide guide, String stepId, int direction)
	{
		List<GuideStep> steps = guide.allSteps();
		int from = guide.stepPositionIndex().getOrDefault(stepId, -1);
		if (from < 0)
		{
			return null;
		}
		for (int i = from + direction; i >= 0 && i < steps.size(); i += direction)
		{
			if (!steps.get(i).isBackground())
			{
				return steps.get(i).getId().getValue();
			}
		}
		return null;
	}

}
