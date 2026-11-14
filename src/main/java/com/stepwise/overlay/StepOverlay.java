package com.stepwise.overlay;

import com.stepwise.StepwiseConfig;
import com.stepwise.engine.CoreEngine;
import com.stepwise.model.PlayerState;
import com.stepwise.panel.TextMarkup;
import com.stepwise.snapshot.AssumptionNudge;
import com.stepwise.snapshot.CompletionCriterion;
import com.stepwise.snapshot.CriterionProgress;
import com.stepwise.snapshot.CurrentStepSummary;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.GuidePosition;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.ProgressBarComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * In-game overlay showing the current guide step, criteria, and progress.
 * Movable and resizable.
 */
public class StepOverlay extends OverlayPanel
{
	private static final Color COLOR_MET = new Color(0, 180, 0);
	private static final Color COLOR_UNMET = new Color(180, 180, 180);
	private static final Color COLOR_PROGRESS_BG = new Color(60, 60, 60);
	private static final Color COLOR_PROGRESS_FG = new Color(80, 180, 80);
	private static final Color COLOR_BANK_NUDGE = new Color(255, 220, 100);

	/** Wider than OverlayPanel's default to reduce text wrapping. */
	private static final int DEFAULT_WIDTH = 220;

	private final StepwiseConfig config;
	/**
	 * Volatile: set at startUp and cleared at shutDown, while the client thread renders.
	 * Read once per frame, since shutDown can clear it mid-render.
	 */
	private volatile CoreEngine coreEngine;
	/** The nudges {@link #cachedAssumptionLine} was built from. Render thread only. */
	private List<AssumptionNudge> cachedNudges;
	private String cachedAssumptionLine;
	/** The position {@link #cachedCounter} was built from. Render thread only. */
	private GuidePosition cachedCounterPosition;
	private String cachedCounter;

	@Inject
	public StepOverlay(StepwiseConfig config)
	{
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_LOW);
		setResizable(true);
		panelComponent.setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
	}

	public void setCoreEngine(CoreEngine coreEngine)
	{
		this.coreEngine = coreEngine;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		CoreEngine engine = coreEngine;
		if (!config.showStepOverlay() || engine == null)
		{
			return null;
		}

		DashboardSnapshot snapshot = engine.getLastSnapshot();
		if (snapshot == null)
		{
			return null;
		}

		CurrentStepSummary step = snapshot.getCurrentStep();
		if (step == null)
		{
			renderNoStep(snapshot);
		}
		else
		{
			renderGuideStep(step, snapshot, engine.getPlayerState());
		}
		return super.render(graphics);
	}

	private void renderNoStep(DashboardSnapshot snapshot)
	{
		GuidePosition pos = snapshot.getGuidePosition();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Stepwise")
			.color(Color.CYAN)
			.build());
		if (pos != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(stepCounter(pos))
				.build());

			panelComponent.getChildren().add(LineComponent.builder()
				.left(TextMarkup.plainInstruction(pos.getInstruction()))
				.build());
		}
		else
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Finding your step...")
				.build());
		}
	}

	/**
	 * The step counter, rebuilt only when the snapshot's position changes: it formats with
	 * {@code String.format}, which is too costly to run every frame.
	 */
	private String stepCounter(GuidePosition pos)
	{
		if (pos != cachedCounterPosition)
		{
			cachedCounterPosition = pos;
			cachedCounter = pos.stepCounter();
		}
		return cachedCounter;
	}

	private void renderGuideStep(CurrentStepSummary step, DashboardSnapshot snapshot,
		PlayerState state)
	{
		GuidePosition pos = snapshot.getGuidePosition();
		String title = "Stepwise";
		if (pos != null)
		{
			title = stepCounter(pos);
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(title)
			.color(Color.CYAN)
			.build());

		// Strip ~~markup~~: Graphics2D cannot render strikethrough.
		if (pos != null && pos.getChapter() != null)
		{
			String breadcrumb = TextMarkup.stripMarkup(pos.getChapter());
			if (pos.getSection() != null && !pos.getSection().equals(pos.getChapter()))
			{
				breadcrumb += " > " + TextMarkup.stripMarkup(pos.getSection());
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left(breadcrumb)
				.leftColor(Color.GRAY)
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(TextMarkup.plainInstruction(step.getInstruction()))
			.build());

		// Only with the bank open: the panel keeps a reminder for stock held anywhere up until
		// the player restocks, but on the game view it would cover the scene for many steps.
		String assumptionLine = state != null && state.isBankOpen()
			? assumptionLine(step.getAssumptionNudges()) : null;
		if (assumptionLine != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(assumptionLine)
				.leftColor(COLOR_BANK_NUDGE)
				.build());
		}

		List<CompletionCriterion> criteria = step.getCriteria();
		if (criteria != null)
		{
			for (CompletionCriterion criterion : criteria)
			{
				renderCriterion(criterion);
			}
		}
	}

	/**
	 * The one line the overlay gives the route-assumption reminders: the first, with a count
	 * of the rest, so an open bank never pushes the criteria far down. Cached by list
	 * identity, since the snapshot's list is fixed until the next snapshot replaces it.
	 */
	String assumptionLine(List<AssumptionNudge> nudges)
	{
		if (nudges == null || nudges.isEmpty())
		{
			return null;
		}
		if (nudges != cachedNudges)
		{
			cachedNudges = nudges;
			String first = nudges.get(0).getReminder();
			cachedAssumptionLine = nudges.size() == 1
				? first
				: first + " (+" + (nudges.size() - 1) + " more)";
		}
		return cachedAssumptionLine;
	}

	private void renderCriterion(CompletionCriterion criterion)
	{
		String icon = criterion.isMet() ? "\u2713" : "\u2022";
		// Amber when a bank withdrawal would satisfy an unmet inventory requirement
		// (see CompletionCriterion.inBankCount). Matches the panel's hint.
		Color color = criterion.isMet() ? COLOR_MET
			: criterion.getInBankCount() > 0 ? COLOR_BANK_NUDGE : COLOR_UNMET;

		// A waived criterion arrives met because the step it serves is done, not because the
		// game state meets it, so like the panel's row it says "(done)".
		String description = criterion.isWaived()
			? criterion.getDescription() + " (done)"
			: criterion.getDescription();
		panelComponent.getChildren().add(LineComponent.builder()
			.left(icon + " " + description)
			.leftColor(color)
			.build());

		CriterionProgress progress = criterion.getProgress();
		if (progress != null && progress.getRequired() > 1 && !criterion.isMet())
		{
			ProgressBarComponent progressBar = new ProgressBarComponent();
			progressBar.setMinimum(0);
			progressBar.setMaximum(progress.getRequired());
			progressBar.setValue(progress.getCurrent());
			progressBar.setLabelDisplayMode(ProgressBarComponent.LabelDisplayMode.PERCENTAGE);
			progressBar.setBackgroundColor(COLOR_PROGRESS_BG);
			progressBar.setForegroundColor(COLOR_PROGRESS_FG);

			panelComponent.getChildren().add(progressBar);

			if (progress.getBankNudge() != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("  " + progress.getBankNudge())
					.leftColor(COLOR_BANK_NUDGE)
					.build());
			}
		}
	}
}
