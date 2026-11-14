package com.stepwise.panel;

import com.stepwise.engine.CoreEngine;
import com.stepwise.guide.StepId;
import com.stepwise.snapshot.CompletionCriterion;
import com.stepwise.snapshot.DashboardSnapshot;
import com.stepwise.snapshot.DetailedStep;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.client.ui.FontManager;

/**
 * Shows recently completed and skipped steps with Undo buttons, behind a collapsible
 * toggle label from {@link PanelSections#foldToggle}.
 */
public class HistoryPanel extends JPanel
{
	private static final Color COLOR_HEADER = PanelColors.HEADER;
	private static final Color COLOR_STEP = new Color(120, 180, 120);
	/** A skipped step: the muted label grey, so it does not read as done. */
	private static final Color COLOR_SKIPPED = PanelColors.HEADER;
	static final String DONE_MARK = "✓ ";
	/** Reads as "passed over". */
	static final String SKIPPED_MARK = "» ";

	private final JLabel toggle;
	private final JPanel contentPanel;
	private boolean expanded = false;
	/** Rebuilds the rows only when the steps listed change. */
	private final RebuildGuard guard = new RebuildGuard();
	private CoreEngine rowsEngine;
	private IconProvider rowsIconProvider;

	public HistoryPanel()
	{
		PanelSections.column(this);
		setBorder(PanelSections.gap(PanelSections.FOLD_GAP));

		contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setOpaque(false);
		contentPanel.setVisible(false);

		toggle = PanelSections.foldToggle("Recent");
		toggle.addMouseListener(PanelSections.clicks(() ->
		{
			expanded = !expanded;
			toggle.setText(PanelSections.foldText("Recent", expanded));
			contentPanel.setVisible(expanded);
			revalidate();
		}));
		add(toggle);
		add(contentPanel);

		setVisible(false);
	}

	/**
	 * Redraws the rows when the set of steps listed changes. Called on every snapshot: a
	 * Mark Done or Skip from the In Progress list changes the rows without moving the
	 * current step.
	 */
	public void rebuild(DashboardSnapshot snapshot, CoreEngine coreEngine, IconProvider iconProvider)
	{
		if (snapshot == null)
		{
			contentPanel.removeAll();
			guard.reset();
			setVisible(false);
			return;
		}
		setVisible(true);

		List<DetailedStep> recent = snapshot.getRecentCompleted();
		// Rows hold the engine their Undo acts on and icons from the provider, so a new one
		// of either redraws them.
		if (coreEngine != rowsEngine || iconProvider != rowsIconProvider)
		{
			guard.reset();
			rowsEngine = coreEngine;
			rowsIconProvider = iconProvider;
		}
		// A row shows its step's instruction and criteria icons, both fixed per step, and a
		// done or skipped mark, so the step ids and that mark are the whole structure.
		if (!guard.shouldRebuild(signature(recent)))
		{
			return;
		}
		contentPanel.removeAll();
		if (recent == null || recent.isEmpty())
		{
			contentPanel.add(PanelSections.smallLabel("No completed steps yet", COLOR_HEADER));
			contentPanel.revalidate();
			return;
		}

		JPanel container = PanelSections.box();

		for (int i = 0; i < recent.size(); i++)
		{
			if (i > 0)
			{
				container.add(PanelColors.divider());
			}
			container.add(buildHistoryRow(recent.get(i), coreEngine, iconProvider));
		}

		contentPanel.add(container);
		contentPanel.revalidate();
	}

	/** Each row's step id paired with whether it is skipped. */
	private static List<List<Object>> signature(List<DetailedStep> steps)
	{
		List<List<Object>> rows = new ArrayList<>();
		if (steps != null)
		{
			for (DetailedStep step : steps)
			{
				rows.add(List.of(step.getStepId(), step.isSkipped()));
			}
		}
		return rows;
	}

	private JPanel buildHistoryRow(DetailedStep step, CoreEngine coreEngine,
		IconProvider iconProvider)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));

		JPanel iconStrip = buildIconStrip(step.getCriteria(), iconProvider);
		if (iconStrip != null)
		{
			row.add(iconStrip, BorderLayout.WEST);
		}

		String text = step.getInstruction();
		if (text == null)
		{
			text = "";
		}

		JTextArea instrText = StepCardPanel.textArea(
			(step.isSkipped() ? SKIPPED_MARK : DONE_MARK) + text,
			step.isSkipped() ? COLOR_SKIPPED : COLOR_STEP, FontManager.getRunescapeSmallFont());
		instrText.setToolTipText(step.isSkipped() ? "Skipped" : null);
		row.add(instrText, BorderLayout.CENTER);

		if (coreEngine != null)
		{
			JButton undoBtn = StepCardPanel.smallButton(new JButton("Undo"));
			Dimension size = new Dimension(56, 20);
			undoBtn.setPreferredSize(size);
			undoBtn.setMinimumSize(size);
			undoBtn.setMaximumSize(size);
			undoBtn.addActionListener(e -> coreEngine.undoStep(new StepId(step.getStepId())));
			row.add(undoBtn, BorderLayout.EAST);
		}

		return row;
	}

	/** Returns a strip of the criteria's icons, or null when none resolve. */
	private static JPanel buildIconStrip(List<CompletionCriterion> criteria,
		IconProvider iconProvider)
	{
		if (criteria == null || criteria.isEmpty() || iconProvider == null)
		{
			return null;
		}

		JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
		strip.setOpaque(false);
		boolean hasIcon = false;

		for (CompletionCriterion c : criteria)
		{
			ImageIcon icon = iconProvider.getIcon(c.getIconType(), c.getIconKey(), false);
			if (icon != null)
			{
				strip.add(new JLabel(icon));
				hasIcon = true;
			}
		}

		return hasIcon ? strip : null;
	}
}
