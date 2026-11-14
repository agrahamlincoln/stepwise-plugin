package com.stepwise.panel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The sidebar's section chrome: the header label, the fold toggle, the gap above a section
 * and the box around its rows. Every section builds these here, so they cannot drift apart.
 */
final class PanelSections
{
	/** Gap above the Recent fold: smaller than a section's, since a fold is not a section. */
	static final int FOLD_GAP = 4;
	/**
	 * Gap above a section that stands on its own (the entry block, Restock, In Progress,
	 * Upcoming, Timers), and above the Data fold, which belongs to none of them.
	 */
	static final int SECTION_GAP = 8;

	/**
	 * Between a header and its content. The 2px left puts every header and fold arrow on one
	 * vertical line.
	 */
	static final Border HEADER_INSET = BorderFactory.createEmptyBorder(0, 2, 3, 0);

	/** Space between chips in a row. */
	static final int CHIP_GAP = 2;

	private static final Color SECTION_HEADER = Color.WHITE;
	private static final Color CHIP_BG = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color CHIP_OPEN_BG = ColorScheme.DARK_GRAY_HOVER_COLOR;

	// Shared, so a section that rebuilds every refresh sets the same border instance and
	// setBorder fires no relayout.
	private static final Border FOLD_GAP_BORDER = BorderFactory.createEmptyBorder(FOLD_GAP, 0, 0, 0);
	private static final Border SECTION_GAP_BORDER = BorderFactory.createEmptyBorder(SECTION_GAP, 0, 0, 0);

	private static final String SHUT = "▸ ";
	private static final String OPEN = "▾ ";

	private PanelSections()
	{
	}

	/** The gap above a section, set as the section wrapper's border. */
	static Border gap(int px)
	{
		if (px == FOLD_GAP)
		{
			return FOLD_GAP_BORDER;
		}
		if (px == SECTION_GAP)
		{
			return SECTION_GAP_BORDER;
		}
		return BorderFactory.createEmptyBorder(px, 0, 0, 0);
	}

	/**
	 * A section header that does not fold. White bold, so it outranks the tan group headings
	 * inside a section and the grey fold headers.
	 */
	static JLabel header(String text)
	{
		return label(text, SECTION_HEADER, FontManager.getRunescapeBoldFont());
	}

	/**
	 * Gives a label the section header's colour and font, for a header row that carries
	 * {@link #HEADER_INSET} itself because it holds a second label (the entry block's title
	 * and duration).
	 */
	static void styleAsHeader(JLabel label)
	{
		label.setForeground(SECTION_HEADER);
		label.setFont(FontManager.getRunescapeBoldFont());
	}

	/**
	 * A fold's header, shut. The caller adds the click handling and sets the text through
	 * {@link #foldText} as the fold opens and shuts. Grey small type: a fold holds detail
	 * on demand, below the sections in rank.
	 */
	static JLabel foldToggle(String label)
	{
		JLabel toggle = label(foldText(label, false), PanelColors.HEADER,
			FontManager.getRunescapeSmallFont());
		toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return toggle;
	}

	/** A fold header's text: the label after a small arrow pointing right when shut, down when open. */
	static String foldText(String label, boolean open)
	{
		return (open ? OPEN : SHUT) + label;
	}

	/** The bordered box a section's rows sit in. Its border is the 1px line alone, no padding. */
	static JPanel box()
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(PanelColors.CONTAINER_BG);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.setBorder(BorderFactory.createLineBorder(PanelColors.CONTAINER_BORDER, 1));
		return box;
	}

	/** A one-line label in the small RuneScape font. */
	static JLabel smallLabel(String text, Color colour)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(colour);
		return label;
	}

	/** Gives a panel a transparent, left-aligned column layout that stacks its children. */
	static <T extends JPanel> T column(T panel)
	{
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/** A new transparent, left-aligned column. */
	static JPanel column()
	{
		return column(new JPanel());
	}

	/**
	 * A chip's border: a 1px line in {@code line} around the label's padding. Shared instances
	 * matter: {@link #styleChip} compares borders by identity, so a fresh border per refresh
	 * would repaint the chip every tick.
	 */
	static Border chipBorder(Color line)
	{
		return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line),
			BorderFactory.createEmptyBorder(3, 5, 3, 5));
	}

	/** A transparent row of chips. FlowLayout's leading gap lines the first chip up with a header's 2px inset. */
	static JPanel chipRow()
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, CHIP_GAP, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	/** A clickable chip, shut: small text in {@code colour} on the chip background. */
	static JLabel chip(String text, Color colour, Border shut, Runnable onClick)
	{
		JLabel chip = smallLabel(text, colour);
		chip.setOpaque(true);
		chip.setBackground(CHIP_BG);
		chip.setBorder(shut);
		chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chip.addMouseListener(clicks(onClick));
		return chip;
	}

	/** Shows a chip open (white on the hover background) or shut. Writes nothing when unchanged. */
	static void styleChip(JComponent chip, boolean open, Border openBorder, Border shutBorder,
		Color shutColour)
	{
		if (chip.getBorder() == (open ? openBorder : shutBorder))
		{
			return;
		}
		chip.setBorder(open ? openBorder : shutBorder);
		chip.setBackground(open ? CHIP_OPEN_BG : CHIP_BG);
		chip.setForeground(open ? Color.WHITE : shutColour);
	}

	/** A listener that runs {@code action} on each click. */
	static MouseAdapter clicks(Runnable action)
	{
		return new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}
		};
	}

	/** A callout's left rule: 2px in {@code colour}, then the text's padding. */
	static Border callout(Color colour)
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, colour),
			BorderFactory.createEmptyBorder(1, 5, 1, 0));
	}

	private static JLabel label(String text, Color colour, Font font)
	{
		JLabel label = smallLabel(text, colour);
		label.setFont(font);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(HEADER_INSET);
		return label;
	}
}
