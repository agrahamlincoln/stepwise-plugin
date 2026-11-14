package com.stepwise.panel;

import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.StepContext;
import com.stepwise.guide.StepId;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The current entry, above the step card: a title line naming it, then three chips for its
 * original text, the items it asks for, and how the account compares with the route. One
 * chip is open at a time, and a new entry starts with all of them shut.
 *
 * <p>{@link ItemsNeededView} and {@link RouteDeficitPanel} keep their own views and update
 * logic. This block's chips open and shut them.
 */
class EntryBlock extends JPanel
{
	private static final Color COLOR_CHIP_TEXT = PanelColors.HEADER;
	private static final Color COLOR_DURATION = new Color(140, 140, 120);
	private static final Color COLOR_TEXT = new Color(180, 180, 160);

	private static final Border CHIP_SHUT = PanelSections.chipBorder(PanelColors.CONTAINER_BORDER);
	private static final Border CHIP_OPEN = PanelSections.chipBorder(ColorScheme.BRAND_ORANGE);

	/** The route chip's indicator icons, sized to the chip's text so the row keeps its height. */
	static final int CHIP_ICON_SIZE = 12;
	/** Space between the title and the duration beside it. */
	private static final int TITLE_GAP = 4;
	private static final int CHIP_GAP = PanelSections.CHIP_GAP;
	/** Room the first row must have to spare before the route chip moves back up to it. */
	static final int CHIP_RETURN_MARGIN = 20;
	private static final Border PART_FIRST = BorderFactory.createEmptyBorder();
	/** Space between two route indicators. */
	private static final Border PART_NEXT = BorderFactory.createEmptyBorder(0, 5, 0, 0);

	private static final String TEXT_TOOLTIP = "The guide's own words for this entry";
	private static final String ITEMS_TOOLTIP = "Items this entry asks for, and whether you have them";

	/** Which chip is open. */
	enum Open
	{
		NONE, TEXT, ITEMS, ROUTE
	}

	private final ItemsNeededView items;
	private final RouteDeficitPanel route;

	/** The current step's section header: the entry and the step's place in it. */
	private final JLabel title = PanelSections.smallLabel(" ", COLOR_CHIP_TEXT);
	/** The title, with the entry's duration on the right. */
	private final JPanel titleRow;
	private final JLabel duration = PanelSections.smallLabel(" ", COLOR_DURATION);
	private final JLabel textChip;
	private final JLabel itemsChip;
	/** One chip holding an indicator each for GP, skills and quests against the guide. */
	private final JPanel routeChip;
	private final JLabel gpPart;
	private final JLabel skillsPart;
	private final JLabel questsPart;
	/** The three indicators in chip order. */
	private final JLabel[] routeParts;
	private final JPanel chipRow;
	private final JPanel routeRow;
	private final JPanel textBody;
	/** The entry's source text: its links inline, or as rows when they end the text. */
	private final LinkedText text;

	private Open open = Open.NONE;
	private String entryId;
	/** Set on a new entry, so the route chip is placed without {@link #CHIP_RETURN_MARGIN}. */
	private boolean placeAfresh = true;
	/** The current step's place in its entry, recounted only when the step changes. */
	private String stepId;
	private int stepNumber;
	private int stepCount;
	/** Whether the entry has original text, so the Text chip has something to open. */
	private boolean hasText;

	EntryBlock(ItemsNeededView items, RouteDeficitPanel route)
	{
		this.items = items;
		this.route = route;

		PanelSections.column(this);
		setBorder(PanelSections.gap(PanelSections.SECTION_GAP));

		// The title is the current step's section header; the duration stays muted beside it.
		PanelSections.styleAsHeader(title);
		// The hgap keeps a space between a long title and the duration.
		titleRow = new JPanel(new BorderLayout(TITLE_GAP, 0));
		titleRow.setOpaque(false);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		titleRow.setBorder(PanelSections.HEADER_INSET);
		titleRow.add(title, BorderLayout.WEST);
		titleRow.add(duration, BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));

		textChip = PanelSections.chip("Guide text", COLOR_CHIP_TEXT, CHIP_SHUT, () -> toggle(Open.TEXT));
		textChip.setToolTipText(TEXT_TOOLTIP);
		itemsChip = PanelSections.chip("Items", COLOR_CHIP_TEXT, CHIP_SHUT, () -> toggle(Open.ITEMS));
		itemsChip.setToolTipText(ITEMS_TOOLTIP);
		gpPart = routePart();
		skillsPart = routePart();
		questsPart = routePart();
		routeParts = new JLabel[]{gpPart, skillsPart, questsPart};
		routeChip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		routeChip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		routeChip.addMouseListener(PanelSections.clicks(() -> toggle(Open.ROUTE)));
		routeChip.add(gpPart);
		routeChip.add(skillsPart);
		routeChip.add(questsPart);
		routeChip.setVisible(false);
		style(routeChip, false);
		chipRow = PanelSections.chipRow();
		chipRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		chipRow.add(textChip);
		chipRow.add(itemsChip);
		chipRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, chipRow.getPreferredSize().height));
		// The route chip's own row, used when it does not fit beside the other two chips.
		routeRow = PanelSections.chipRow();
		routeRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		routeRow.add(routeChip);
		routeRow.setMaximumSize(chipRow.getMaximumSize());
		routeRow.setVisible(false);

		text = new LinkedText(COLOR_TEXT, FontManager.getRunescapeSmallFont());
		text.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 4));
		textBody = PanelSections.box();
		textBody.add(text);
		textBody.setVisible(false);

		add(titleRow);
		add(chipRow);
		add(routeRow);
		add(textBody);
		add(items);
		add(route);
		setVisible(false);
	}

	/**
	 * A block with the title line and the Guide text chip only. Its items view and route
	 * panel are never fed, so they stay hidden and their chips with them.
	 */
	static EntryBlock textOnly()
	{
		return new EntryBlock(new ItemsNeededView(), new RouteDeficitPanel());
	}

	/**
	 * Follows the current step. Called on every refresh, after the items view and the route
	 * panel have taken the same snapshot. Every write is a no-op when nothing changed, so an
	 * unchanged refresh triggers no layout.
	 *
	 * @param context the current step's context, or null when there is no current step
	 */
	void update(StepContext context, String currentStepId, StepwiseGuide guide)
	{
		if (context == null || context.getEntryId() == null)
		{
			setVisible(false);
			entryId = null;
			stepId = null;
			return;
		}

		// The title, the duration and the text are written only when the entry or step changes.
		boolean newEntry = !Objects.equals(context.getEntryId(), entryId);
		if (newEntry)
		{
			entryId = context.getEntryId();
			open = Open.NONE;
			placeAfresh = true;
			String original = context.getOriginalText();
			hasText = original != null && !original.isEmpty();
			// Source text: its asterisks and underscores are literal, so no inline markup. Set
			// here, on a new entry only, so a same-entry refresh never rebuilds the wrapping text.
			text.setMarkdown(hasText ? original : "", false);
			String time = StepwisePanel.formatEntryDuration(context.getEntryDurationMinutes());
			duration.setText(time != null ? time : "");
		}
		if (newEntry || !Objects.equals(currentStepId, stepId))
		{
			stepId = currentStepId;
			countStep(guide);
			title.setText(titleOf(entryId, stepNumber, stepCount));
		}

		textChip.setVisible(hasText);
		itemsChip.setVisible(items.hasItems());
		itemsChip.setText("Items " + items.itemCount());
		updateRouteChip();

		// Close the open chip if it has just been hidden.
		if ((open == Open.TEXT && !textChip.isVisible())
			|| (open == Open.ITEMS && !itemsChip.isVisible())
			|| (open == Open.ROUTE && !routeChip.isVisible()))
		{
			open = Open.NONE;
		}
		applyOpen();
		setVisible(true);
	}

	/**
	 * One indicator each for GP, skills and quests, shown only once it has a reading. Every
	 * write below is guarded or a no-op on an unchanged value, because this runs on every
	 * refresh.
	 */
	private void updateRouteChip()
	{
		routeChip.setVisible(route.hasComparison());

		boolean gp = route.isGpKnown();
		setPart(gpPart, gp, route.coinsIcon(CHIP_ICON_SIZE), "Coins", gpText(route.gpDelta()),
			RouteDeficitPanel.toneOf(route.gpDelta()),
			gpTooltip(route.gpDelta(), route.isSkipOnOffer()));

		boolean skills = route.isSkillsKnown();
		int skillsDelta = countDelta(route.skillsBehind(), route.skillsAhead());
		setPart(skillsPart, skills, route.skillsIcon(CHIP_ICON_SIZE), "Skills",
			countText(skillsDelta), RouteDeficitPanel.toneOf(skillsDelta),
			countsTooltip("skill", "skills", route.skillsBehind(), route.skillsAhead()));

		boolean quests = route.isQuestsKnown();
		int questsDelta = countDelta(route.questsBehind(), route.questsAhead());
		setPart(questsPart, quests, route.questIcon(CHIP_ICON_SIZE), "Quests",
			countText(questsDelta), RouteDeficitPanel.toneOf(questsDelta),
			countsTooltip("quest", "quests", route.questsBehind(), route.questsAhead()));

		// The first indicator shown sits on the chip's own padding; the others keep apart.
		boolean first = true;
		for (JLabel part : routeParts)
		{
			if (!part.isVisible())
			{
				continue;
			}
			Border border = first ? PART_FIRST : PART_NEXT;
			if (part.getBorder() != border)
			{
				part.setBorder(border);
			}
			first = false;
		}
		placeRouteChip();
	}

	/**
	 * Puts the route chip beside the other chips when the row has room, and on a row of its
	 * own when it has not. FlowLayout would wrap an overflowing chip onto a line the row's
	 * fixed height never shows, so the chip would vanish.
	 *
	 * <p>The chip comes back up only with {@link #CHIP_RETURN_MARGIN} to spare. Its figures
	 * change width as coins move, and without the margin a player near the edge would see the
	 * whole panel below shift by a row every few ticks.
	 */
	private void placeRouteChip()
	{
		boolean inFirstRow = routeChip.getParent() == chipRow;
		int chip = routeChip.getPreferredSize().width + CHIP_GAP;
		int others = chipRow.getPreferredSize().width
			- (inFirstRow && routeChip.isVisible() ? chip : 0);
		boolean fits = fitsFirstRow(others + chip, inFirstRow, placeAfresh);
		placeAfresh = false;
		if (fits != inFirstRow)
		{
			(fits ? chipRow : routeRow).add(routeChip);
			chipRow.revalidate();
			routeRow.revalidate();
		}
		routeRow.setVisible(!fits && routeChip.isVisible());
	}

	/**
	 * Whether the route chip belongs on the first row, which it needs {@code needed} pixels of.
	 * A new entry places afresh: the margin only damps movement within one entry.
	 */
	static boolean fitsFirstRow(int needed, boolean inFirstRow, boolean afresh)
	{
		return needed <= StepwisePanel.CONTENT_WIDTH - (inFirstRow || afresh ? 0 : CHIP_RETURN_MARGIN);
	}

	private static void setPart(JLabel part, boolean shown, Icon icon, String name, String text,
		Color tone, String tooltip)
	{
		part.setVisible(shown);
		if (!shown)
		{
			return;
		}
		if (part.getIcon() != icon)
		{
			part.setIcon(icon);
		}
		// Without the icon to say which figure this is, the text names it.
		part.setText(icon != null ? text : name + " " + text);
		part.setForeground(tone);
		if (!tooltip.equals(part.getToolTipText()))
		{
			part.setToolTipText(tooltip);
		}
	}

	/**
	 * The direction a count indicator shows: minus the behind count when anything is behind,
	 * else the ahead count. Behind wins because it is the one a player can act on.
	 */
	static int countDelta(int behind, int ahead)
	{
		return behind > 0 ? -behind : ahead;
	}

	/** "-2", "+5", or "0" when the player is level with the guide. */
	static String countText(int delta)
	{
		return delta == 0 ? "0" : (delta > 0 ? "+" : "-") + Math.abs(delta);
	}

	/** "+1.2M", "-120k", or "0" when the player's coins match the guide's plan. */
	static String gpText(long delta)
	{
		return delta == 0 ? "0" : RouteDeficitPanel.signed(delta);
	}

	/** "1.2M gp ahead of the guide", plus the skip offer when there is one. */
	static String gpTooltip(long delta, boolean skipOnOffer)
	{
		String reading = delta == 0
			? "Coins level with the guide"
			: RouteDeficitPanel.compact(Math.abs(delta)) + " gp "
				+ (delta > 0 ? "ahead of" : "behind") + " the guide";
		return skipOnOffer ? reading + ", enough to skip this money-making stretch" : reading;
	}

	/** "2 quests behind the guide, 5 ahead", leaving out a count of zero. */
	static String countsTooltip(String one, String many, int behind, int ahead)
	{
		if (behind == 0 && ahead == 0)
		{
			return Character.toUpperCase(many.charAt(0)) + many.substring(1)
				+ " level with the guide";
		}
		if (behind == 0)
		{
			return ahead + " " + (ahead == 1 ? one : many) + " ahead of the guide";
		}
		return behind + " " + (behind == 1 ? one : many) + " behind the guide"
			+ (ahead > 0 ? ", " + ahead + " ahead" : "");
	}

	/**
	 * The title line: the entry, plus "N of M" when the entry has several steps and
	 * the current one was found among them.
	 */
	static String titleOf(String entryId, int stepNumber, int stepCount)
	{
		return "Entry " + entryId
			// The count omits the word "step" so the longest titles fit beside the duration in
			// the bold header font.
			+ (stepNumber > 0 && stepCount > 1 ? " · " + stepNumber + " of " + stepCount : "");
	}

	/** Where the current step sits among its entry's steps. Runs only when the step changes. */
	private void countStep(StepwiseGuide guide)
	{
		stepNumber = 0;
		stepCount = 0;
		if (guide == null)
		{
			return;
		}
		Map<StepId, StepContext> contexts = guide.stepContextMap();
		for (GuideStep step : guide.allSteps())
		{
			StepContext context = contexts.get(step.getId());
			if (context == null || !entryId.equals(context.getEntryId()))
			{
				// An entry's steps are contiguous, so the first one past them ends the count.
				if (stepCount > 0)
				{
					break;
				}
				continue;
			}
			stepCount++;
			if (step.getId().getValue().equals(stepId))
			{
				stepNumber = stepCount;
			}
		}
	}

	private void toggle(Open chip)
	{
		open = open == chip ? Open.NONE : chip;
		applyOpen();
		updateRouteChip();
		revalidate();
		repaint();
	}

	private void applyOpen()
	{
		if (textBody.isVisible() != (open == Open.TEXT))
		{
			textBody.setVisible(open == Open.TEXT);
		}
		items.setOpen(open == Open.ITEMS);
		route.setOpen(open == Open.ROUTE);
		style(textChip, open == Open.TEXT);
		style(itemsChip, open == Open.ITEMS);
		// The route chip's indicators keep their own tones open or shut; its border and
		// background show it open.
		style(routeChip, open == Open.ROUTE);
	}

	private JLabel routePart()
	{
		JLabel part = PanelSections.smallLabel(" ", COLOR_CHIP_TEXT);
		part.setIconTextGap(2);
		part.setVisible(false);
		// A label with a tooltip takes the mouse events, so each one opens the chip itself.
		part.addMouseListener(PanelSections.clicks(() -> toggle(Open.ROUTE)));
		return part;
	}

	private static void style(JComponent chip, boolean isOpen)
	{
		PanelSections.styleChip(chip, isOpen, CHIP_OPEN, CHIP_SHUT, COLOR_CHIP_TEXT);
	}

	// --- Test seams ---

	Open openForTest()
	{
		return open;
	}

	/**
	 * How wide the wider of the two chip rows would be with these labels, in the panel's own
	 * font, with all three route indicators showing a blank icon of the chip's icon size.
	 */
	int chipRowWidthForTest(String text, String items, String gp, String skills, String quests)
	{
		textChip.setText(text);
		itemsChip.setText(items);
		textChip.setVisible(true);
		itemsChip.setVisible(true);
		Icon icon = new ImageIcon(new BufferedImage(CHIP_ICON_SIZE, CHIP_ICON_SIZE,
			BufferedImage.TYPE_INT_ARGB));
		setPart(gpPart, true, icon, "Coins", gp, COLOR_CHIP_TEXT, "");
		setPart(skillsPart, true, icon, "Skills", skills, COLOR_CHIP_TEXT, "");
		setPart(questsPart, true, icon, "Quests", quests, COLOR_CHIP_TEXT, "");
		gpPart.setBorder(PART_FIRST);
		skillsPart.setBorder(PART_NEXT);
		questsPart.setBorder(PART_NEXT);
		routeChip.setVisible(true);
		placeRouteChip();
		return Math.max(chipRow.getPreferredSize().width, routeRow.getPreferredSize().width);
	}

	/** Whether the route chip sits beside the other chips rather than on its own row. */
	boolean routeChipInFirstRowForTest()
	{
		return routeChip.getParent() == chipRow;
	}

	/** Moves to an entry that asks for nothing, so the Items chip is hidden. */
	void newEntryWithoutItemsForTest()
	{
		itemsChip.setVisible(false);
		placeAfresh = true;
		placeRouteChip();
	}

	/** The route chip's indicator texts, in order, for those showing. */
	List<String> routePartsForTest()
	{
		List<String> texts = new ArrayList<>();
		for (JLabel part : routeParts)
		{
			if (part.isVisible())
			{
				texts.add(part.getText());
			}
		}
		return texts;
	}

	/** Simulates a click on the chip. */
	void toggleForTest(Open chip)
	{
		toggle(chip);
	}

	/** How wide the title line would be with this title and duration, in their own fonts. */
	int titleRowWidthForTest(String titleText, String durationText)
	{
		title.setText(titleText);
		duration.setText(durationText);
		return titleRow.getInsets().left + title.getPreferredSize().width + TITLE_GAP
			+ duration.getPreferredSize().width;
	}

	String titleForTest()
	{
		return title.getText();
	}

	LinkedText textForTest()
	{
		return text;
	}
}
