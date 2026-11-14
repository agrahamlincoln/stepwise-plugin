package com.stepwise.lamp;

import com.stepwise.StepwiseConfig;
import com.stepwise.model.Skill;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Outlines the guide's skill on the XP reward chooser, with the guide's reason as a tooltip.
 *
 * <p>Draws only: it adds no menu entries, handles no clicks, and leaves the chooser's own
 * widgets untouched. When the recommended skill is faded on this lamp it draws the policy's
 * fallback instead, or nothing. It reads the game's own fade rather than repeating the game's
 * eligibility rules.
 *
 * <p>The recommendation is resolved once when the chooser opens; a frame reads only it, the
 * widgets and the mouse.
 */
public class LampHighlightOverlay extends Overlay
{
	private static final Color OUTLINE = new Color(0, 255, 255);
	private static final Color FILL = new Color(0, 255, 255, 65);
	private static final Stroke STROKE = new BasicStroke(2);

	/** Whether a skill's button can be chosen on the open chooser. */
	interface SkillProbe
	{
		boolean isSelectable(Skill skill);
	}

	private final Client client;
	private final StepwiseConfig config;
	private final TooltipManager tooltipManager;
	private final SkillProbe probe = this::isLayerSelectable;

	/**
	 * The open chooser's recommendation and its tooltip, swapped together as one value.
	 * Volatile: the event handlers and render run on the client thread, but plugin start and
	 * stop clear it from the EDT.
	 */
	private volatile Session session;

	/** A recommendation with the tooltip built for it once, not per frame. */
	private static final class Session
	{
		final LampRecommendation recommendation;
		final Tooltip tooltip;

		Session(LampRecommendation recommendation)
		{
			this.recommendation = recommendation;
			this.tooltip = recommendation.getNote().isEmpty() ? null : new Tooltip(recommendation.getNote());
		}
	}

	@Inject
	public LampHighlightOverlay(Client client, StepwiseConfig config, TooltipManager tooltipManager)
	{
		this.client = client;
		this.config = config;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Show {@code rec} for the chooser now open; null when it closes or nothing applies. */
	public void setRecommendation(@Nullable LampRecommendation rec)
	{
		session = rec != null ? new Session(rec) : null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Session current = session;
		if (current == null)
		{
			return null;
		}
		Skill target = highlightTarget(
			config.highlightLampSkill(), current.recommendation, probe);
		if (target == null)
		{
			return null;
		}
		Widget layer = client.getWidget(XpRewardSkills.componentId(target));
		if (layer == null || layer.isHidden())
		{
			return null;
		}
		Rectangle bounds = layer.getBounds();
		graphics.setColor(FILL);
		graphics.fill(bounds);
		graphics.setColor(OUTLINE);
		graphics.setStroke(STROKE);
		graphics.draw(bounds);

		if (current.tooltip != null)
		{
			Point mouse = client.getMouseCanvasPosition();
			if (mouse != null && bounds.contains(mouse.getX(), mouse.getY()))
			{
				tooltipManager.add(current.tooltip);
			}
		}
		return null;
	}

	/**
	 * The skill to outline: the recommended one when selectable, else its fallback when that
	 * is, else none.
	 */
	@Nullable
	static Skill highlightTarget(boolean enabled, @Nullable LampRecommendation rec, SkillProbe probe)
	{
		if (!enabled || rec == null)
		{
			return null;
		}
		if (probe.isSelectable(rec.getSkill()))
		{
			return rec.getSkill();
		}
		Skill fallback = rec.getFallback();
		if (fallback != null && fallback != rec.getSkill() && probe.isSelectable(fallback))
		{
			return fallback;
		}
		return null;
	}

	private boolean isLayerSelectable(Skill skill)
	{
		return isSelectable(client.getWidget(XpRewardSkills.componentId(skill)));
	}

	/**
	 * Whether the chooser lets the player pick this skill button.
	 *
	 * <p>The chooser's update script rebuilds each button layer's children: nine button tiles,
	 * then the skill icon. A selectable skill leaves all of them at transparency 0. One the
	 * player lacks the level or quest for gets its icon set to 150; one this lamp does not
	 * offer gets every child set to 220. So any child above 0 means faded. A layer with no
	 * children yet, before the script has run, reads as selectable.
	 */
	static boolean isSelectable(@Nullable Widget layer)
	{
		if (layer == null || layer.isHidden())
		{
			return false;
		}
		Widget[] children = layer.getChildren();
		if (children == null)
		{
			return true;
		}
		for (Widget child : children)
		{
			if (child != null && child.getOpacity() != 0)
			{
				return false;
			}
		}
		return true;
	}
}
