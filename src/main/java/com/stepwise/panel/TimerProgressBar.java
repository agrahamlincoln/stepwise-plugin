package com.stepwise.panel;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import javax.swing.JComponent;

/**
 * A thin horizontal progress bar whose fill grows left to right. Progress is fixed at
 * construction, so each rebuild makes a new bar.
 */
class TimerProgressBar extends JComponent
{
	private static final Color TRACK = new Color(48, 48, 48);
	private static final Color FILL_GROWING = new Color(100, 160, 100);
	private static final int HEIGHT = 5;

	private final float progress;
	private final Color fill;

	/** A timer's bar, green while it fills. Clamps progress to [0, 1]. */
	TimerProgressBar(double progress)
	{
		this(progress, FILL_GROWING);
	}

	/**
	 * A bar in the given colour, for a measure with no good or bad end, such as how far
	 * through the guide an account is. Clamps progress to [0, 1].
	 */
	TimerProgressBar(double progress, Color fill)
	{
		this.progress = (float) Math.max(0.0, Math.min(1.0, progress));
		this.fill = fill;
		setOpaque(false);
		setPreferredSize(new Dimension(0, HEIGHT));
		setMinimumSize(new Dimension(0, HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int w = getWidth();
		int h = getHeight();
		g.setColor(TRACK);
		g.fillRect(0, 0, w, h);
		g.setColor(fill);
		g.fillRect(0, 0, Math.round(w * progress), h);
	}
}
