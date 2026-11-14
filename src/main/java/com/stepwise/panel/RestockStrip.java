package com.stepwise.panel;

import com.stepwise.snapshot.AssumptionNudge;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.border.Border;
import net.runelite.client.ui.FontManager;

/**
 * Under the current step card, a Restock section boxed like In Progress: one amber chip per
 * route assumption the player is not meeting, each an item icon and a short name. Clicking a
 * chip opens its reminder under the chips, and clicking it again shuts it. One reminder is
 * open at a time.
 *
 * <p>The chips follow the snapshot, so a chip goes away by itself once the player restocks.
 * They rebuild only when the set of nudges changes; an unchanged tick touches nothing.
 */
class RestockStrip extends JPanel
{
	private static final Color COLOR_AMBER = PanelColors.AMBER;

	private static final Border CHIP_SHUT = PanelSections.chipBorder(COLOR_AMBER);
	private static final Border CHIP_OPEN = PanelSections.chipBorder(COLOR_AMBER.brighter());

	static final int CHIP_ICON_SIZE = 14;
	private static final int CHIP_GAP = PanelSections.CHIP_GAP;
	private static final int ROW_GAP = 2;

	private final JPanel box = PanelSections.box();
	private final JPanel chips = PanelSections.column();
	private final JPanel callout = new JPanel(new BorderLayout());
	private final JTextArea calloutText;
	private final RebuildGuard guard = new RebuildGuard();
	private final List<JLabel> chipLabels = new ArrayList<>();

	private IconProvider iconProvider;
	private List<AssumptionNudge> nudges = List.of();
	/** The nudge whose reminder is open, or null with all shut. */
	private AssumptionNudge open;

	RestockStrip()
	{
		PanelSections.column(this);
		setBorder(PanelSections.gap(PanelSections.SECTION_GAP));
		JLabel header = PanelSections.header("Restock");

		calloutText = StepCardPanel.textArea(" ", COLOR_AMBER, FontManager.getRunescapeSmallFont());
		callout.setOpaque(false);
		callout.setAlignmentX(LEFT_ALIGNMENT);
		callout.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(4, 2, 0, 0), PanelSections.callout(COLOR_AMBER)));
		callout.add(calloutText, BorderLayout.CENTER);
		callout.setVisible(false);

		// The box's 1px line, padded inside. The chip rows' own 2px FlowLayout gap makes up the
		// rest of the left inset.
		box.setBorder(BorderFactory.createCompoundBorder(
			box.getBorder(),
			BorderFactory.createEmptyBorder(4, 2, 4, 4)));
		box.add(chips);
		box.add(callout);

		add(header);
		add(box);
		guard.shouldRebuild(List.of());
		setVisible(false);
	}

	/**
	 * Follows the current step's nudges. Called on every refresh.
	 *
	 * @param nudges the current step's nudges; null or empty hides the strip
	 */
	void update(List<AssumptionNudge> nudges, IconProvider iconProvider)
	{
		List<AssumptionNudge> next = nudges == null ? List.of() : nudges;
		this.iconProvider = iconProvider;
		if (!guard.shouldRebuild(next))
		{
			return;
		}
		this.nudges = next;
		if (open != null && !next.contains(open))
		{
			open = null;
		}
		buildChips();
		applyOpen();
		setVisible(!next.isEmpty());
		revalidate();
		repaint();
	}

	/**
	 * Packs the chips into rows that fit the panel's content width. FlowLayout would report
	 * one row however narrow the panel, and a chip pushed to a second row would never show.
	 */
	private void buildChips()
	{
		chips.removeAll();
		chipLabels.clear();
		JPanel row = null;
		int rowWidth = 0;
		for (AssumptionNudge nudge : nudges)
		{
			JLabel chip = chip(nudge);
			int width = chip.getPreferredSize().width + CHIP_GAP;
			if (row == null || rowWidth + width > innerWidth())
			{
				if (row != null)
				{
					chips.add(Box.createVerticalStrut(ROW_GAP));
				}
				row = PanelSections.chipRow();
				chips.add(row);
				rowWidth = CHIP_GAP;
			}
			row.add(chip);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
			rowWidth += width;
			chipLabels.add(chip);
		}
	}

	private JLabel chip(AssumptionNudge nudge)
	{
		JLabel chip = PanelSections.chip(nudge.getLabel(), COLOR_AMBER, CHIP_SHUT, () -> toggle(nudge));
		chip.setIconTextGap(4);
		chip.setToolTipText(nudge.getReminder());
		ImageIcon icon = iconProvider != null && nudge.getItemId() > 0
			? iconProvider.getItemIcon(nudge.getItemId(), CHIP_ICON_SIZE) : null;
		if (icon != null)
		{
			chip.setIcon(icon);
		}
		// A row always takes its first chip, so a label wider than the box would run past its
		// edge. Capped, the JLabel ends the text with "..."; the reminder, shown as the tooltip
		// and when the chip opens, still names what ran out in full.
		int widest = innerWidth() - 2 * CHIP_GAP;
		Dimension pref = chip.getPreferredSize();
		if (pref.width > widest)
		{
			chip.setPreferredSize(new Dimension(widest, pref.height));
		}
		return chip;
	}

	private void toggle(AssumptionNudge nudge)
	{
		open = Objects.equals(open, nudge) ? null : nudge;
		applyOpen();
		revalidate();
		repaint();
	}

	private void applyOpen()
	{
		for (int i = 0; i < chipLabels.size(); i++)
		{
			PanelSections.styleChip(chipLabels.get(i), nudges.get(i).equals(open), CHIP_OPEN,
				CHIP_SHUT, COLOR_AMBER);
		}
		if (open == null)
		{
			callout.setVisible(false);
			return;
		}
		calloutText.setText(open.getReminder());
		// Pre-size so the reminder wraps at its real width on first paint.
		int width = innerWidth() - callout.getInsets().left - callout.getInsets().right;
		calloutText.setSize(width, Short.MAX_VALUE);
		calloutText.setSize(width, calloutText.getPreferredSize().height);
		callout.setVisible(true);
	}

	/** The width inside the box: the panel's content width less the box's border and padding. */
	int innerWidth()
	{
		return StepwisePanel.CONTENT_WIDTH - box.getInsets().left - box.getInsets().right;
	}

	// --- Test seams ---

	/** The callout's text area, so a test can check it was sized before first paint. */
	JTextArea calloutTextForTest()
	{
		return calloutText;
	}
}
