package com.stepwise.panel;

import java.awt.Color;
import java.awt.Graphics;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/**
 * One inventory square: a sprite and its stack count.
 *
 * <p>Painted by hand because a Swing label puts its text beside the icon, and the count
 * belongs on top of the sprite.
 */
class ItemSquare extends JPanel
{
	/**
	 * An item sprite (36×32) plus a margin. Sprites draw at full size because shrunken seed
	 * sprites are hard to tell apart.
	 */
	static final int SIZE = 42;
	static final int GAP = 3;

	/** The game's own stack-count colour. */
	private static final Color COLOR_COUNT = Color.YELLOW;

	private final ImageIcon sprite;
	private final String count;

	ItemSquare(ImageIcon sprite, String count)
	{
		this.sprite = sprite;
		this.count = count;
		// Transparent so the InventoryBackdrop slot colour shows through.
		setOpaque(false);
		setFont(FontManager.getRunescapeSmallFont());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (sprite != null)
		{
			sprite.paintIcon(this, g,
				(getWidth() - sprite.getIconWidth()) / 2,
				(getHeight() - sprite.getIconHeight()) / 2);
		}
		if (count != null)
		{
			g.setFont(getFont());
			g.setColor(COLOR_COUNT);
			g.drawString(count, 3, g.getFontMetrics().getAscent() + 1);
		}
	}
}
