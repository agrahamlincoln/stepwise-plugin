package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.engine.SnapshotBuilder;
import com.stepwise.guide.StepId;
import com.stepwise.snapshot.CompletionCriterion;
import com.stepwise.snapshot.CriterionProgress;
import com.stepwise.snapshot.PreStepSummary;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.Border;
import javax.swing.text.DefaultCaret;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Card showing a single guide step: instruction text, completion criteria
 * with progress bars, and action buttons (Mark Done / Skip).
 *
 * <p>An active card has an orange left accent and full colour, with Mark Done, Skip and the
 * {@code <} and {@code >} preview arrows when it has an engine to act on. A previewed card
 * takes the accent {@link #showAsPreview} gives it. A non-active card is muted and has no
 * buttons, except an In Progress card ({@link #inProgress}), which keeps Mark Done and Skip
 * inside a Details fold. A previewed card and the welcome page's read-only card get the
 * arrows through {@link #addPaging}.
 */
public class StepCardPanel extends JPanel
{
	private static final Color COLOR_MET = new Color(34, 197, 94);
	private static final Color COLOR_UNMET = PanelColors.HEADER;
	private static final Color COLOR_BANK_NUDGE = PanelColors.AMBER;
	private static final Color COLOR_PROGRESS_BG = new Color(46, 46, 46);
	private static final Color COLOR_PROGRESS_FG = ColorScheme.BRAND_ORANGE;
	private static final Color COLOR_PROGRESS_PENDING = new Color(
		ColorScheme.BRAND_ORANGE.getRed(),
		ColorScheme.BRAND_ORANGE.getGreen(),
		ColorScheme.BRAND_ORANGE.getBlue(),
		90);
	private static final Color COLOR_DONE_BG = new Color(38, 120, 38);
	private static final Color COLOR_DONE_FG = new Color(220, 255, 220);
	private static final Color COLOR_GUIDANCE = new Color(180, 180, 180);
	private static final Color COLOR_GUIDANCE_TOGGLE = new Color(150, 150, 150);
	private static final Color COLOR_SKIP_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color COLOR_SKIP_FG = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color COLOR_LEVEL_MARKER = new Color(255, 255, 255, 60);
	private static final Color COLOR_SUBTLE = PanelColors.SUBTLE;
	private static final Color COLOR_PRESTEP = new Color(160, 160, 160);
	/**
	 * The "From now on" callout's text and 2px left rule: a muted teal that reads as standing
	 * advice, apart from the orange current-step bar and the amber bank hints.
	 */
	private static final Color COLOR_FROM_NOW_ON = new Color(120, 170, 160);

	private final CoreEngine coreEngine;
	private final String stepId;
	private final boolean active;
	private final IconProvider iconProvider;

	/** Criteria rows and progress bars, rebuilt by {@link #updateCriteria} only on a structural change. */
	private JPanel criteriaContainer;
	/** Pre-step rows, rebuilt by {@link #updatePreSteps} only on a structural change. */
	private JPanel preStepContainer;
	/** Progress bars updated in place on same-structure refreshes. */
	private final List<ProgressBarPanel> progressBars = new ArrayList<>();
	/** Label components per criterion row, recoloured in place by {@link #applyBankHint}. */
	private final List<CriterionRowView> criterionRows = new ArrayList<>();
	/** Rebuilds the criteria container only when its structure changes. */
	private final RebuildGuard criteriaGuard = new RebuildGuard();
	/** Rebuilds the pre-step container only when its structure changes. */
	private final RebuildGuard preStepGuard = new RebuildGuard();
	/** The "From now on" callout; null on a card that is not the current step. */
	private JPanel fromNowOnContainer;

	/** Mark Done and Skip's width: room for two 18px preview arrows beside them on the card. */
	private static final int ROW_BUTTON_WIDTH = 74;

	/** The Skip button, or null when the card has none. */
	private ConfirmButton skipButton;

	/** Whether the criteria are alternatives, any one of which completes the step. */
	private boolean criteriaAlternatives;

	public StepCardPanel(CoreEngine coreEngine, String stepId, String instruction,
		String guidance, List<CompletionCriterion> criteria,
		boolean active, IconProvider iconProvider)
	{
		this(coreEngine, stepId, instruction, guidance, criteria, active,
			iconProvider, null, false, null, false, criteria == null || criteria.isEmpty());
	}

	/**
	 * The active step's card.
	 *
	 * @param criteriaAlternatives whether the criteria are alternatives, any one of which
	 *                             completes the step
	 * @param completedByClick     whether nothing the game reports at this step completes it,
	 *                             so Mark Done is the expected action rather than a fallback
	 */
	public StepCardPanel(CoreEngine coreEngine, String stepId, String instruction,
		String guidance, List<CompletionCriterion> criteria,
		boolean active, IconProvider iconProvider,
		List<PreStepSummary> preSteps,
		boolean guidanceCollapsed, Consumer<Boolean> onGuidanceToggle,
		boolean criteriaAlternatives, boolean completedByClick)
	{
		this.coreEngine = coreEngine;
		this.stepId = stepId;
		this.active = active;
		this.iconProvider = iconProvider;
		this.criteriaAlternatives = criteriaAlternatives;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setAlignmentX(LEFT_ALIGNMENT);
		setBackground(active
			? ColorScheme.DARKER_GRAY_COLOR
			: ColorScheme.DARKER_GRAY_COLOR.darker());

		if (active)
		{
			setBorder(activeBorder(ColorScheme.BRAND_ORANGE));
		}
		else
		{
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createCompoundBorder(
					BorderFactory.createEmptyBorder(2, 0, 2, 0),
					BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR.darker(), 1)
				),
				BorderFactory.createEmptyBorder(5, 6, 5, 4)
			));
		}

		if (active)
		{
			preStepContainer = PanelSections.column();
			populatePreSteps(preStepContainer, preSteps);
			// Prime the guard so the first refresh tick is seen as a no-op.
			preStepGuard.shouldRebuild(preStepSignature(preSteps));
			add(preStepContainer);
		}

		JTextArea instrText = textArea(instruction,
			active ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR,
			active ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		add(instrText);

		if (active)
		{
			// Above the Details fold so a collapsed card still shows it.
			fromNowOnContainer = PanelSections.column();
			add(fromNowOnContainer);
		}

		if (active && guidance != null && !guidance.isEmpty())
		{
			add(Box.createVerticalStrut(3));
			add(buildGuidanceSection(guidance, null, guidanceCollapsed, onGuidanceToggle));
		}

		criteriaContainer = PanelSections.column();
		populateCriteria(criteriaContainer, criteria);
		// Prime the guard so the first refresh tick is seen as a no-op.
		criteriaGuard.shouldRebuild(criteriaSignature(criteria, criteriaAlternatives));
		add(criteriaContainer);

		JPanel buttonRow = active ? buildButtonRow(completedByClick) : null;
		if (buttonRow != null)
		{
			// The < and > arrows only look: unlike Undo, they let a player reread an earlier
			// step or read ahead without changing their progress. Of the live cards only the
			// current one has them, since an In Progress step sits outside the guide's order.
			buttonRow.add(PreviewBar.paging(stepId, coreEngine.getGuide(), coreEngine::previewStep),
				BorderLayout.EAST);
			add(Box.createVerticalStrut(4));
			add(buttonRow);
		}
	}

	/**
	 * Adds the preview arrows to a card built without buttons, such as a previewed card or the
	 * welcome page's read-only card, in the corner where the current card carries them.
	 *
	 * @param action a button for Mark Done's place, or null
	 */
	void addPaging(JComponent action, JPanel paging)
	{
		JPanel row = buttonRow();
		if (action != null)
		{
			row.add(action, BorderLayout.WEST);
		}
		row.add(paging, BorderLayout.EAST);
		add(Box.createVerticalStrut(4));
		add(row);
	}

	private static JPanel buttonRow()
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		return row;
	}

	private static Border activeBorder(Color accent)
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createCompoundBorder(
				BorderFactory.createEmptyBorder(2, 0, 2, 0),
				BorderFactory.createMatteBorder(1, 3, 1, 1, accent)
			),
			BorderFactory.createEmptyBorder(6, 6, 6, 4)
		);
	}

	/**
	 * Marks the card as a preview of a step that is not the player's own: {@code accent}
	 * replaces the orange of the current step.
	 */
	void showAsPreview(Color accent)
	{
		setBorder(activeBorder(accent));
	}

	// --- In-place update methods ---

	private static void populatePreSteps(JPanel container, List<PreStepSummary> preSteps)
	{
		container.removeAll();
		if (preSteps != null && !preSteps.isEmpty())
		{
			for (PreStepSummary ps : preSteps)
			{
				container.add(buildPreStepRow(ps));
			}
			container.add(Box.createVerticalStrut(3));
		}
	}

	private void populateCriteria(JPanel container, List<CompletionCriterion> criteria)
	{
		progressBars.clear();
		criterionRows.clear();
		container.removeAll();
		if (criteria != null && !criteria.isEmpty())
		{
			container.add(Box.createVerticalStrut(active ? 6 : 4));
			// With several criteria, a muted line says whether the player needs all of
			// them or any one.
			if (active && criteria.size() > 1)
			{
				container.add(textArea(criteriaSummary(criteria, criteriaAlternatives),
					COLOR_GUIDANCE_TOGGLE, FontManager.getRunescapeSmallFont()));
				container.add(Box.createVerticalStrut(3));
			}
			for (int i = 0; i < criteria.size(); i++)
			{
				CompletionCriterion criterion = criteria.get(i);
				container.add(buildCriterionRow(criterion));

				CriterionProgress progress = criterion.getProgress();
				if (isCounting(criterion))
				{
					ProgressBarPanel bar = new ProgressBarPanel(progress.getTargetLevel() > 0);
					bar.setProgress(progress);
					progressBars.add(bar);
					container.add(bar);
					if (progress.getBankNudge() != null)
					{
						JTextArea nudge = textArea("  " + progress.getBankNudge(),
							COLOR_BANK_NUDGE, FontManager.getRunescapeSmallFont());
						container.add(nudge);
					}
				}

				if (i < criteria.size() - 1)
				{
					container.add(Box.createVerticalStrut(2));
				}
			}
		}
	}

	/**
	 * Updates the criteria without rebuilding the card. Progress changes update the
	 * existing bars in place. The rows rebuild only when their structure changes
	 * (count, description, met or waived state).
	 */
	public void updateCriteria(List<CompletionCriterion> criteria)
	{
		updateCriteria(criteria, criteriaAlternatives);
	}

	/**
	 * As {@link #updateCriteria(List)}, also setting whether the criteria are alternatives.
	 * A change to {@code alternatives} rebuilds the rows and the summary line together.
	 */
	public void updateCriteria(List<CompletionCriterion> criteria, boolean alternatives)
	{
		criteriaAlternatives = alternatives;
		if (criteriaGuard.shouldRebuild(criteriaSignature(criteria, alternatives)))
		{
			populateCriteria(criteriaContainer, criteria);
			criteriaContainer.revalidate();
			criteriaContainer.repaint();
		}
		else
		{
			// Same structure: update bar values and the bank hint in place.
			int barIdx = 0;
			for (int i = 0; i < criteria.size(); i++)
			{
				CompletionCriterion c = criteria.get(i);
				if (i < criterionRows.size())
				{
					applyBankHint(criterionRows.get(i), c);
				}
				if (isCounting(c) && barIdx < progressBars.size())
				{
					progressBars.get(barIdx).setProgress(c.getProgress());
					barIdx++;
				}
			}
		}
	}

	/** Whether a criterion is still counting towards a target, so it draws a progress bar. */
	static boolean isCounting(CompletionCriterion criterion)
	{
		CriterionProgress p = criterion.getProgress();
		return p != null && p.getRequired() > 1 && !criterion.isMet() && !criterion.isWaived();
	}

	/**
	 * Structural signature for the criteria rows: description, met and waived state.
	 * Progress counts and the bank hint stay out. Both change on ordinary play (XP drops,
	 * bank transactions) and would rebuild the wrapping text areas into flicker.
	 */
	private static List<String> criteriaSignature(List<CompletionCriterion> criteria,
		boolean alternatives)
	{
		if (criteria == null)
		{
			return null;
		}
		List<String> sig = new ArrayList<>(criteria.size() + 1);
		// Sets the summary line's wording.
		sig.add(alternatives ? "any" : "all");
		for (CompletionCriterion c : criteria)
		{
			sig.add(c.getDescription() + "\0" + c.isMet() + "\0" + c.isWaived());
		}
		return sig;
	}

	/**
	 * Updates the pre-step rows, rebuilding them only when their text or met marker
	 * changes. A progress-only refresh is a no-op, so the row JTextAreas are not
	 * recreated per tick (see {@link RebuildGuard}).
	 */
	public void updatePreSteps(List<PreStepSummary> preSteps)
	{
		if (preStepContainer != null
			&& preStepGuard.shouldRebuild(preStepSignature(preSteps)))
		{
			populatePreSteps(preStepContainer, preSteps);
			preStepContainer.revalidate();
			preStepContainer.repaint();
		}
	}

	/** Structural signature for the pre-step rows: text plus the met marker, all a row renders. */
	private static List<String> preStepSignature(List<PreStepSummary> preSteps)
	{
		if (preSteps == null)
		{
			return null;
		}
		List<String> sig = new ArrayList<>(preSteps.size());
		for (PreStepSummary ps : preSteps)
		{
			sig.add(ps.getInstruction() + " " + preStepMet(ps));
		}
		return sig;
	}

	private static boolean preStepMet(PreStepSummary preStep)
	{
		return preStep.getCriterion() != null && preStep.getCriterion().isMet();
	}

	/**
	 * Shows the step's "From now on" callout under the instruction: a standing habit that
	 * starts here. Fixed for the step, so the owner sets it once when it builds the card; a
	 * same-step refresh never touches it. Does nothing on a card that is not the current step.
	 *
	 * @param text the callout, or null for none
	 */
	public void setFromNowOn(String text)
	{
		if (fromNowOnContainer == null)
		{
			return;
		}
		fromNowOnContainer.removeAll();
		if (text != null && !text.isBlank())
		{
			JPanel callout = new JPanel(new BorderLayout());
			callout.setOpaque(false);
			callout.setAlignmentX(LEFT_ALIGNMENT);
			callout.setBorder(PanelSections.callout(COLOR_FROM_NOW_ON));
			callout.add(textArea("From now on: " + text.strip(), COLOR_FROM_NOW_ON,
				FontManager.getRunescapeSmallFont()), BorderLayout.CENTER);
			fromNowOnContainer.add(Box.createVerticalStrut(4));
			fromNowOnContainer.add(callout);
		}
		fromNowOnContainer.revalidate();
	}

	/** Sizes the card to {@code width} before first paint, so its text areas wrap at that width. */
	void presize(int width)
	{
		if (width > 0)
		{
			setSize(width, getPreferredSize().height);
			doLayout();
		}
	}

	/**
	 * A card for the In Progress list. It keeps the muted look of any non-active card, and
	 * adds a Details fold below the criteria holding the step's guidance and its Mark Done
	 * and Skip row. A background step never holds the active slot, so this fold is the only
	 * place its guidance can be read and the only way a manual one can be completed.
	 *
	 * <p>The fold always appears, guidance or not, because it carries the buttons. Keeping
	 * them inside it leaves a collapsed card no taller than the instruction and criteria, so a
	 * list of several grinds stays short.
	 *
	 * @param completedByClick whether nothing the game reports at this step completes it, so
	 *                         Mark Done is the expected action and the prominent button
	 * @param detailsCollapsed whether the fold starts shut
	 * @param onDetailsToggle  told the new collapsed state on each click, so the owner can
	 *                         rebuild the card in the same state; may be null
	 */
	static StepCardPanel inProgress(CoreEngine coreEngine, String stepId, String instruction,
		String guidance, List<CompletionCriterion> criteria, boolean completedByClick,
		boolean detailsCollapsed, Consumer<Boolean> onDetailsToggle, IconProvider iconProvider)
	{
		StepCardPanel card = new StepCardPanel(coreEngine, stepId, instruction, null,
			criteria, false, iconProvider);
		card.add(Box.createVerticalStrut(3));
		card.add(card.buildGuidanceSection(guidance, card.buildButtonRow(completedByClick),
			detailsCollapsed, onDetailsToggle));
		return card;
	}

	/**
	 * Builds the collapsible Details section: the guidance, then {@code actions} when given.
	 * Markdown links that end the guidance render as rows below it; any other link renders
	 * inline (see {@link LinkedText}).
	 *
	 * @param guidance the step's guidance; null or blank adds no text
	 * @param actions  a row placed under the guidance inside the fold; null for none
	 */
	private JPanel buildGuidanceSection(String guidance, JComponent actions,
		boolean startCollapsed, Consumer<Boolean> onToggle)
	{
		JPanel wrapper = PanelSections.column();
		JPanel content = PanelSections.column();

		boolean hasGuidance = guidance != null && !guidance.isBlank();
		if (hasGuidance)
		{
			LinkedText guidanceText = new LinkedText(COLOR_GUIDANCE, FontManager.getRunescapeSmallFont());
			guidanceText.setMarkdown(guidance.strip(), true);
			content.add(guidanceText);
		}
		if (actions != null)
		{
			content.add(Box.createVerticalStrut(hasGuidance ? 4 : 2));
			content.add(actions);
		}

		content.setVisible(!startCollapsed);

		JLabel toggle = new JLabel(PanelSections.foldText("Details", !startCollapsed));
		toggle.setForeground(COLOR_GUIDANCE_TOGGLE);
		toggle.setFont(FontManager.getRunescapeSmallFont());
		toggle.setAlignmentX(LEFT_ALIGNMENT);
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		toggle.addMouseListener(PanelSections.clicks(() ->
		{
			boolean nowCollapsed = content.isVisible();
			content.setVisible(!nowCollapsed);
			toggle.setText(PanelSections.foldText("Details", !nowCollapsed));
			if (nowCollapsed && actions != null && skipButton != null)
			{
				// A Skip armed inside the fold must not be waiting, unseen, for the next
				// time it opens.
				skipButton.disarm();
			}
			if (onToggle != null)
			{
				onToggle.accept(nowCollapsed);
			}
			wrapper.revalidate();
		}));

		wrapper.add(toggle);
		wrapper.add(content);
		return wrapper;
	}

	/** A pre-step row: a green check when met, a muted arrow when not, then the instruction. */
	private static JPanel buildPreStepRow(PreStepSummary preStep)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);

		boolean met = preStepMet(preStep);
		String prefix = met ? "\u2713 " : "\u25B8 ";
		Color color = met ? COLOR_MET : COLOR_PRESTEP;

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridy = 0;
		gbc.gridx = 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.anchor = GridBagConstraints.WEST;

		JTextArea text = textArea(prefix + preStep.getInstruction(),
			color, FontManager.getRunescapeSmallFont());
		row.add(text, gbc);

		return row;
	}

	private JPanel buildCriterionRow(CompletionCriterion criterion)
	{
		JPanel row = new JPanel(new GridBagLayout());
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);

		Color color = criterionColor(criterion);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.CENTER;
		gbc.gridy = 0;

		ImageIcon icon = iconProvider != null
			? iconProvider.getIcon(criterion.getIconType(), criterion.getIconKey(), active)
			: null;

		if (icon != null)
		{
			JLabel iconLabel = new JLabel(icon);
			iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
			gbc.gridx = 0;
			gbc.weightx = 0;
			gbc.fill = GridBagConstraints.NONE;
			row.add(iconLabel, gbc);
		}

		String description = criterion.isWaived()
			? criterion.getDescription() + " (done)"
			: criterion.getDescription();
		JTextArea lbl = textArea(description,
			color, FontManager.getRunescapeSmallFont());
		gbc.gridx = icon != null ? 1 : 0;
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		row.add(lbl, gbc);

		JTextArea countLabel = null;
		if (criterion.getProgress() != null
			&& criterion.getProgress().getRequired() > 1
			&& !isCounting(criterion))
		{
			countLabel = textArea(
				String.format(Locale.ROOT, "%,d/%,d",
					criterion.getProgress().getCurrent(), criterion.getProgress().getRequired()),
				color, FontManager.getRunescapeSmallFont());
			countLabel.setLineWrap(false);
			gbc.gridx = gbc.gridx + 1;
			gbc.weightx = 0;
			gbc.fill = GridBagConstraints.NONE;
			gbc.anchor = GridBagConstraints.EAST;
			row.add(countLabel, gbc);
		}

		CriterionRowView view = new CriterionRowView(lbl, countLabel);
		criterionRows.add(view);
		applyBankHint(view, criterion);

		Dimension pref = row.getPreferredSize();
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));

		return row;
	}

	/**
	 * Row colour: waived criteria fade out, met criteria go green, and an unmet
	 * criterion with stock in the bank turns amber to prompt a withdrawal.
	 */
	private static Color criterionColor(CompletionCriterion criterion)
	{
		if (criterion.isWaived())
		{
			return COLOR_SUBTLE;
		}
		if (criterion.isMet())
		{
			return COLOR_MET;
		}
		return criterion.getInBankCount() > 0 ? COLOR_BANK_NUDGE : COLOR_UNMET;
	}

	/**
	 * Applies the bank hint (amber colour and tooltip) to an existing row's labels.
	 * The hint can flip on any bank transaction, so it updates in place and must never
	 * force a structural rebuild.
	 */
	private static void applyBankHint(CriterionRowView view, CompletionCriterion criterion)
	{
		Color color = criterionColor(criterion);
		String tooltip = null;
		if (!criterion.isMet() && !criterion.isWaived() && criterion.getInBankCount() > 0)
		{
			tooltip = criterion.getInBankCount() > 1
				? criterion.getInBankCount() + " in your bank — withdraw them"
				: "In your bank — withdraw it";
		}
		view.label.setForeground(color);
		view.label.setToolTipText(tooltip);
		if (view.countLabel != null)
		{
			view.countLabel.setForeground(color);
			view.countLabel.setToolTipText(tooltip);
		}
	}

	/** Label components of one criterion row, retained for in-place hint updates. */
	private static final class CriterionRowView
	{
		final JTextArea label;
		/** Null when the row has a progress bar instead of a count label. */
		final JTextArea countLabel;

		CriterionRowView(JTextArea label, JTextArea countLabel)
		{
			this.label = label;
			this.countLabel = countLabel;
		}
	}

	/**
	 * The line above several criteria: "Need just one" when any one completes the step,
	 * otherwise how many are met. A waived criterion counts as met.
	 */
	static String criteriaSummary(List<CompletionCriterion> criteria, boolean alternatives)
	{
		if (alternatives)
		{
			return "Need just one";
		}
		int met = 0;
		for (CompletionCriterion criterion : criteria)
		{
			if (criterion.isMet())
			{
				met++;
			}
		}
		return met + " of " + criteria.size() + " done";
	}

	/**
	 * Progress bar updated in place via {@link #setProgress}, so a value change repaints
	 * without rebuilding the component tree. Default mode shows counts (items, GP). Skill
	 * mode shows XP with level markers.
	 */
	private static class ProgressBarPanel extends JPanel
	{
		private final boolean skillMode;
		private final Font barFont = FontManager.getRunescapeSmallFont();

		// Written by setProgress(), read by paintComponent(). The label is precomputed
		// so paint does not allocate.
		private int current;
		private int pending;
		private int required;
		private int currentLevel;
		private int targetLevel;
		private int[] milestones;
		private String defaultLabel;

		ProgressBarPanel(boolean skillMode)
		{
			this.skillMode = skillMode;
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			int height = skillMode ? 16 : 14;
			setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
			setPreferredSize(new Dimension(200, height));
			setBorder(BorderFactory.createEmptyBorder());
		}

		void setProgress(CriterionProgress p)
		{
			this.current = p.getCurrent();
			this.pending = p.getPending();
			this.required = p.getRequired();
			this.currentLevel = p.getCurrentLevel();
			this.targetLevel = p.getTargetLevel();
			this.milestones = p.getLevelMilestoneXp();
			this.defaultLabel = pending > 0
				? String.format(Locale.ROOT, "%,d + %,d / %,d", current, pending, required)
				: String.format(Locale.ROOT, "%,d / %,d", current, required);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (skillMode)
			{
				paintSkillBar(g);
			}
			else
			{
				paintDefaultBar(g);
			}
		}

		private void paintDefaultBar(Graphics g)
		{
			int w = getWidth();
			int h = getHeight();
			String label = defaultLabel;

			g.setColor(COLOR_PROGRESS_BG);
			g.fillRect(0, 0, w, h);

			int confirmedWidth = (int) ((long) current * w / required);
			g.setColor(COLOR_PROGRESS_FG);
			g.fillRect(0, 0, confirmedWidth, h);

			if (pending > 0)
			{
				int pendingWidth = (int) ((long) pending * w / required);
				g.setColor(COLOR_PROGRESS_PENDING);
				g.fillRect(confirmedWidth, 0, pendingWidth, h);
			}

			g.setColor(Color.WHITE);
			g.setFont(barFont);
			FontMetrics fm = g.getFontMetrics();
			int textX = (w - fm.stringWidth(label)) / 2;
			int textY = (h + fm.getAscent() - fm.getDescent()) / 2;
			g.drawString(label, textX, textY);
		}

		private void paintSkillBar(Graphics g)
		{
			int w = getWidth();
			int h = getHeight();

			int totalPct = required > 0 ? (int) ((long) current * 100 / required) : 0;
			String centerText = totalPct + "%";
			// Abbreviated: written out, a seven-figure total runs into the centred percentage.
			String rightText = RouteDeficitPanel.compact(current) + " / "
				+ RouteDeficitPanel.compact(required);
			int nextLevel = Math.min(currentLevel + 1, targetLevel);
			String leftText = buildNextLevelText(current, currentLevel, nextLevel, targetLevel, required);

			g.setColor(COLOR_PROGRESS_BG);
			g.fillRect(0, 0, w, h);

			g.setColor(COLOR_PROGRESS_FG);
			g.fillRect(0, 0, (int) ((long) current * w / required), h);

			if (milestones != null)
			{
				g.setColor(COLOR_LEVEL_MARKER);
				for (int xp : milestones)
				{
					int markerX = (int) ((long) xp * w / required);
					g.drawLine(markerX, 0, markerX, h);
				}
			}

			g.setFont(barFont);
			FontMetrics fm = g.getFontMetrics();
			int textY = (h + fm.getAscent() - fm.getDescent()) / 2;

			int centerX = (w - fm.stringWidth(centerText)) / 2;
			int rightX = w - fm.stringWidth(rightText) - 3;

			g.setColor(Color.BLACK);
			g.drawString(leftText, 4, textY + 1);
			g.drawString(centerText, centerX + 1, textY + 1);
			g.drawString(rightText, rightX + 1, textY + 1);

			g.setColor(Color.WHITE);
			g.drawString(leftText, 3, textY);
			g.drawString(centerText, centerX, textY);
			g.drawString(rightText, rightX, textY);
		}
	}

	/**
	 * The skill bar's left text: "X% to LVL" within the current level bracket. At or past
	 * the target level with an XP target still unmet, it shows "X% to goal" instead.
	 */
	private static String buildNextLevelText(int currentXp, int currentLevel,
		int nextLevel, int targetLevel, int requiredXp)
	{
		if (currentLevel >= targetLevel)
		{
			int bracketStart = SnapshotBuilder.xpForLevel(currentLevel);
			int bracketRange = requiredXp - bracketStart;
			if (bracketRange <= 0 || currentXp >= requiredXp)
			{
				return "Done";
			}
			int pct = (int) ((long) (currentXp - bracketStart) * 100 / bracketRange);
			pct = Math.max(0, Math.min(pct, 100));
			return pct + "% to goal";
		}

		int bracketStart = SnapshotBuilder.xpForLevel(currentLevel);
		int bracketEnd = SnapshotBuilder.xpForLevel(nextLevel);
		int bracketRange = bracketEnd - bracketStart;

		int pctToNext = bracketRange > 0
			? (int) ((long) (currentXp - bracketStart) * 100 / bracketRange)
			: 100;
		pctToNext = Math.max(0, Math.min(pctToNext, 100));

		return pctToNext + "% to " + nextLevel;
	}

	/**
	 * Mark Done and Skip, or null on a card without an engine, where nothing clicked could
	 * reach an account.
	 *
	 * @param manual whether the step has nothing the game can detect, so clicking is the
	 *               only way it completes
	 */
	private JPanel buildButtonRow(boolean manual)
	{
		if (coreEngine == null)
		{
			return null;
		}
		JPanel row = buttonRow();
		row.add(buildButtons(manual), BorderLayout.WEST);
		return row;
	}

	/**
	 * Mark Done and Skip, narrower than the other action buttons and with no outer gaps, so
	 * the current card's preview arrows fit beside them on one row.
	 */
	private JPanel buildButtons(boolean manual)
	{
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		buttons.setOpaque(false);

		// Mark Done is prominent only on manual steps. On a detectable step a prominent button
		// invites clicking past detection and latching a step never done, so it takes Skip's
		// quiet style there and stays available for a missed detection.
		JButton doneBtn = manual
			? smallButton(new JButton("Mark Done"), COLOR_DONE_BG, COLOR_DONE_FG)
			: smallButton(new JButton("Mark Done"), COLOR_SKIP_BG, COLOR_SKIP_FG);
		if (!manual)
		{
			doneBtn.setToolTipText("Use this only if you have finished the step and it was not"
				+ " checked off on its own.");
		}
		doneBtn.addActionListener(e -> coreEngine.markStepDone(new StepId(stepId)));

		skipButton = smallButton(new ConfirmButton("Skip", true,
			() -> coreEngine.skipStep(new StepId(stepId))), COLOR_SKIP_BG, COLOR_SKIP_FG);

		buttons.add(doneBtn);
		buttons.add(Box.createHorizontalStrut(4));
		buttons.add(skipButton);
		return buttons;
	}

	/**
	 * Creates a non-editable JTextArea that wraps at the container width. Use it instead
	 * of an HTML JLabel, whose CSS widths do not match the panel's pixel width.
	 */
	static JTextArea textArea(String text, Color color, Font font)
	{
		JTextArea area = new JTextArea(text);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setForeground(color);
		area.setFont(font);
		area.setAlignmentX(LEFT_ALIGNMENT);
		area.setBorder(BorderFactory.createEmptyBorder());
		area.setCaret(new NonScrollingCaret());
		return area;
	}

	/**
	 * Caret that never scrolls its enclosing scroll pane.
	 *
	 * <p>{@code DefaultCaret.adjustVisibility()} calls {@code scrollRectToVisible()}
	 * whenever a rebuilt text component is added. The request bubbles up to the
	 * sidebar's {@code JScrollPane}, which jumps to the new component and loses the
	 * player's scroll position. Panel text is never editable or focusable, so there
	 * is no caret worth following. Package-private so {@link LinkedTextPane} shares it.
	 */
	static final class NonScrollingCaret extends DefaultCaret
	{
		@Override
		protected void adjustVisibility(Rectangle nloc)
		{
			// Empty on purpose: never move the viewport.
		}
	}

	/** Styles a button for an action row: small type, no focus ring or border, 86x20. */
	static <B extends JButton> B smallButton(B btn)
	{
		btn.setFocusPainted(false);
		btn.setPreferredSize(new Dimension(86, 20));
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.setBorderPainted(false);
		return btn;
	}

	/**
	 * A small button {@code width} pixels wide with thin side margins: the look-and-feel's
	 * default margins clip a label such as "Mark Done" once the button is narrower than 86.
	 */
	static <B extends JButton> B smallButton(B btn, int width)
	{
		smallButton(btn).setMargin(new Insets(0, 2, 0, 2));
		btn.setPreferredSize(new Dimension(width, 20));
		return btn;
	}

	/** Mark Done or Skip: {@link #ROW_BUTTON_WIDTH} wide, in the given colours. */
	private static <B extends JButton> B smallButton(B btn, Color bg, Color fg)
	{
		smallButton(btn, ROW_BUTTON_WIDTH).setBackground(bg);
		btn.setForeground(fg);
		return btn;
	}
}
