package com.stepwise.panel;

import java.awt.Color;
import javax.swing.JButton;
import javax.swing.Timer;

/**
 * A button for an action worth a second thought: the first click arms it, reading "Confirm?" in
 * the danger colour, and only a second click acts. {@link #disarm} puts it back to rest.
 */
class ConfirmButton extends JButton
{
	/** How long a timed button waits armed for its confirming click. */
	static final int CONFIRM_MS = 4_000;

	private final String label;
	/**
	 * Disarms a click never confirmed, or null when the button waits until {@link #disarm}.
	 * A card that is not rebuilt would otherwise keep a "Confirm?" waiting for any later stray
	 * click.
	 */
	private final Timer disarmTimer;
	private Color restingColour;
	private boolean armed;

	/**
	 * Styles nothing: the caller sets the font and colours, and the resting colour is read
	 * when the button arms.
	 *
	 * @param timed whether an unconfirmed click disarms after {@link #CONFIRM_MS}
	 */
	ConfirmButton(String label, boolean timed, Runnable action)
	{
		super(label);
		this.label = label;
		if (timed)
		{
			disarmTimer = new Timer(CONFIRM_MS, e -> disarm());
			disarmTimer.setRepeats(false);
		}
		else
		{
			disarmTimer = null;
		}
		addActionListener(e ->
		{
			if (armed)
			{
				disarm();
				action.run();
				return;
			}
			armed = true;
			restingColour = getForeground();
			setText("Confirm?");
			setForeground(PanelColors.DANGER);
			if (disarmTimer != null)
			{
				disarmTimer.restart();
			}
		});
	}

	/** Puts an armed button back to its label, so the next click arms it again. */
	void disarm()
	{
		if (disarmTimer != null)
		{
			disarmTimer.stop();
		}
		if (armed)
		{
			armed = false;
			setText(label);
			setForeground(restingColour);
		}
	}
}
