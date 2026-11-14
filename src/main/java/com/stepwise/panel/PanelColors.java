package com.stepwise.panel;

import java.awt.Color;
import java.awt.Dimension;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * The panel's shared palette and rules. Colours that belong to one view's own look stay in
 * that view's file.
 */
final class PanelColors
{
	/** Muted text for fold headers, labels, unmet criteria, section breadcrumbs. */
	static final Color HEADER = ColorScheme.LIGHT_GRAY_COLOR;
	/** Clickable link text (markdown guidance links, wiki links). */
	static final Color LINK = new Color(100, 180, 255);
	/** De-emphasized text (waived criteria, step IDs, version footers). */
	static final Color SUBTLE = new Color(80, 80, 80);
	/** Destructive action highlights (skip confirm, reset, clear). */
	static final Color DANGER = new Color(255, 51, 51);
	/** Something the player should act on: a bank withdrawal, a restock, behind the route. */
	static final Color AMBER = new Color(199, 122, 24);
	/**
	 * The brand's colours, taken from the game's account-type chat icons: hardcore red from the
	 * hardcore group ironman icon and iron grey from the ironman icon. Only the header wears
	 * them, so red keeps its meaning elsewhere in the panel.
	 */
	static final Color BRAND_RED_LIGHT = new Color(196, 48, 42);
	static final Color BRAND_RED = new Color(148, 22, 21);
	static final Color BRAND_IRON_LIGHT = new Color(124, 129, 130);
	/** Divider line between items in a container. */
	static final Color DIVIDER = new Color(55, 55, 55);
	static final Color CONTAINER_BG = ColorScheme.DARKER_GRAY_COLOR.darker();
	static final Color CONTAINER_BORDER = ColorScheme.MEDIUM_GRAY_COLOR.darker();

	private PanelColors()
	{
	}

	/** A 1px horizontal divider line for use inside list containers. */
	static JPanel divider()
	{
		return divider(DIVIDER);
	}

	/** A full-width 1px horizontal line in {@code colour}. */
	static JPanel divider(Color colour)
	{
		return rule(colour, 1);
	}

	/** A full-width horizontal line {@code height} pixels tall in {@code colour}. */
	static JPanel rule(Color colour, int height)
	{
		JPanel line = new JPanel();
		line.setBackground(colour);
		line.setAlignmentX(JPanel.LEFT_ALIGNMENT);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		line.setPreferredSize(new Dimension(0, height));
		return line;
	}
}
