package com.stepwise.panel;

import com.stepwise.engine.NavigatorState;
import com.stepwise.guide.GuideStep;
import com.stepwise.guide.StepContext;
import com.stepwise.guide.StepId;
import com.stepwise.guide.StepwiseGuide;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.runelite.client.ui.FontManager;

/**
 * The Step Navigator fold: the guide's entries in fixed pages of {@link #PAGE_SIZE}, each page
 * inside one section. The header names the page's section and opens a menu of every section;
 * the footer's one pair of arrows pages through the whole guide. Clicking an entry previews its
 * first step across the whole panel ({@link com.stepwise.engine.CoreEngine#previewStep})
 * without moving the player; the previewed card carries the {@code <} and {@code >} arrows and,
 * behind the player, Move here. Logged out it follows the last account the welcome page shows,
 * and previews it read-only.
 *
 * <p>Starts folded. While folded it draws nothing.
 */
public class StepNavigator extends JPanel
{
	private static final int PAGE_SIZE = 6;

	private static final Color COLOR_DONE = new Color(120, 180, 120);
	private static final Color COLOR_SKIPPED = PanelColors.HEADER;
	private static final Color COLOR_PARTIAL = PanelColors.AMBER;
	private static final Color COLOR_TODO = new Color(160, 160, 160);
	private static final Color COLOR_CURRENT = Color.WHITE;
	private static final Color COLOR_CURRENT_BG = new Color(52, 60, 52);
	/** The previewed entry's row: the preview banner's blue. */
	private static final Color COLOR_PREVIEW_BG = PreviewBar.BANNER_BG;
	private static final Color COLOR_SUMMARY = new Color(170, 170, 160);
	private static final Color COLOR_STEP = new Color(150, 150, 140);
	private static final Color COLOR_DISABLED = new Color(90, 90, 90);

	private static final String TITLE = "Step Navigator";

	private final StepwisePanel panel;
	/** The account the panel showed at the last refresh, which the menu and paging read. EDT only. */
	@Nullable
	private NavigatorState progress;
	private final List<Entry> entries = new ArrayList<>();
	/** Each listed step's id to the index of its entry in {@link #entries}. */
	private final Map<String, Integer> entryOfStep = new HashMap<>();
	/** Each page: its section and the indexes of its entries in {@link #entries}. */
	private final List<Page> pages = new ArrayList<>();
	private final Map<String, String> sectionTitles = new LinkedHashMap<>();
	private final Map<String, String> chapterOf = new LinkedHashMap<>();
	private final Map<String, String> summaries;

	private final JPanel content;
	private boolean expanded;
	/** The page shown, or -1 to follow the previewed entry's page, else the current one's. */
	private int page = -1;
	/** The page the last refresh drew, which ending a preview stays on. */
	private int lastShownPage;
	/** The pages of the current entry and of the entry the page follows, from the last refresh. */
	private int currentPage = -1;
	private int anchorPage = -1;
	/** The previewed step the page last followed, so a change in it is seen once. */
	private String lastPreviewed = "null";
	private Object shownSignature;

	private static final class Entry
	{
		final String id;
		final String section;
		/** Its place in {@link #entries}. */
		final int index;
		final List<GuideStep> steps = new ArrayList<>();

		Entry(String id, String section, int index)
		{
			this.id = id;
			this.section = section;
			this.index = index;
		}
	}

	/**
	 * An entry's or section's status, painted rather than typed: the RuneScape fonts have no
	 * circle glyphs, and the fallback font draws them clipped and blurred.
	 */
	private enum Mark implements Icon
	{
		TODO(COLOR_TODO), CURRENT(COLOR_CURRENT), PARTIAL(COLOR_PARTIAL), DONE(COLOR_DONE),
		SKIPPED(COLOR_SKIPPED);

		private static final int SIZE = 9;
		private static final Stroke LINE = new BasicStroke(1.3f, BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND);
		private static final Stroke TICK = new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
			BasicStroke.JOIN_ROUND);
		private static final Shape RING = new Ellipse2D.Float(0.5f, 0.5f, SIZE - 1, SIZE - 1);
		private static final Shape HALF = new Arc2D.Float(0.5f, 0.5f, SIZE - 1, SIZE - 1,
			90, 180, Arc2D.PIE);
		private static final Shape CHECK = path(1, 4.5f, 3.5f, 7, 8, 1.5f);
		private static final Shape CHEVRONS = chevrons();
		private final Color colour;

		Mark(Color colour)
		{
			this.colour = colour;
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(colour);
			g2.translate(x, y);
			g2.setStroke(LINE);
			switch (this)
			{
				case TODO:
					g2.draw(RING);
					break;
				case CURRENT:
					g2.fill(RING);
					break;
				case PARTIAL:
					g2.draw(RING);
					g2.fill(HALF);
					break;
				case DONE:
					g2.setStroke(TICK);
					g2.draw(CHECK);
					break;
				case SKIPPED:
					g2.draw(CHEVRONS);
					break;
				default:
					break;
			}
			g2.dispose();
		}

		/** An open polyline through the points {@code xy} gives as x, y pairs. */
		private static Path2D path(float... xy)
		{
			Path2D path = new Path2D.Float();
			path.moveTo(xy[0], xy[1]);
			for (int i = 2; i < xy.length; i += 2)
			{
				path.lineTo(xy[i], xy[i + 1]);
			}
			return path;
		}

		/** Two right-pointing chevrons side by side, the skip mark. */
		private static Path2D chevrons()
		{
			Path2D both = path(1, 1.5f, 4, 4.5f, 1, 7.5f);
			both.append(path(5, 1.5f, 8, 4.5f, 5, 7.5f), false);
			return both;
		}
	}

	/** A small filled triangle pointing left ({@code direction} -1) or right (1). */
	private static final class Arrow implements Icon
	{
		private static final int[] Y = {0, 4, 9};
		private final Color colour;
		/** The triangle's x points with the icon's corner at 0, so a paint only translates. */
		private final int[] xs;

		Arrow(Color colour, int direction)
		{
			this.colour = colour;
			int tip = direction < 0 ? 0 : 5;
			int base = 5 - tip;
			this.xs = new int[] {base, tip, base};
		}

		@Override
		public int getIconWidth()
		{
			return 5;
		}

		@Override
		public int getIconHeight()
		{
			return 9;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			g.setColor(colour);
			g.translate(x, y);
			g.fillPolygon(xs, Y, 3);
			g.translate(-x, -y);
		}
	}

	private static final class Page
	{
		final String section;
		final List<Integer> entries = new ArrayList<>();

		Page(String section)
		{
			this.section = section;
		}
	}

	StepNavigator(StepwiseGuide guide, StepwisePanel panel)
	{
		this.panel = panel;
		this.summaries = guide.getEntrySummaries() != null
			? guide.getEntrySummaries() : Collections.emptyMap();
		index(guide);

		PanelSections.column(this);
		setBorder(PanelSections.gap(PanelSections.FOLD_GAP));
		content = PanelSections.column();
		content.setVisible(false);

		JLabel toggle = PanelSections.foldToggle(TITLE);
		toggle.addMouseListener(PanelSections.clicks(() ->
		{
			expanded = !expanded;
			toggle.setText(PanelSections.foldText(TITLE, expanded));
			content.setVisible(expanded);
			// Drawn on opening: a folded navigator skips its redraws.
			shownSignature = null;
			refresh();
			revalidate();
		}));
		add(toggle);
		add(content);
		setVisible(false);
	}

	private void index(StepwiseGuide guide)
	{
		Map<StepId, StepContext> contexts = guide.stepContextMap();
		Map<String, Entry> byId = new LinkedHashMap<>();
		for (GuideStep step : guide.allSteps())
		{
			StepContext ctx = contexts.get(step.getId());
			if (ctx == null || ctx.getEntryId() == null)
			{
				continue;
			}
			Entry entry = byId.get(ctx.getEntryId());
			if (entry == null)
			{
				String section = sectionOf(ctx.getEntryId());
				entry = new Entry(ctx.getEntryId(), section, entries.size());
				byId.put(entry.id, entry);
				Page last = pages.isEmpty() ? null : pages.get(pages.size() - 1);
				if (last == null || !last.section.equals(section) || last.entries.size() == PAGE_SIZE)
				{
					last = new Page(section);
					pages.add(last);
				}
				last.entries.add(entries.size());
				entries.add(entry);
				sectionTitles.putIfAbsent(section, ctx.getSectionTitle());
				chapterOf.putIfAbsent(section, ctx.getChapterTitle());
			}
			if (!step.isBackground())
			{
				entry.steps.add(step);
				entryOfStep.put(step.getId().getValue(), entry.index);
			}
		}
	}

	private static String sectionOf(String entryId)
	{
		String[] parts = entryId.split("\\.");
		return parts.length >= 2 ? parts[0] + "." + parts[1] : entryId;
	}

	// ---------------------------------------------------------------
	// Refresh
	// ---------------------------------------------------------------

	/** Redraws when the current step, the done set or the view changes. EDT only. */
	void refresh()
	{
		// Logged out, the panel shows the last account read-only, so the navigator follows it.
		progress = panel.navigatorProgress();
		// A guide whose steps name no entries has nothing to list.
		setVisible(progress != null && !pages.isEmpty());
		if (!isVisible() || !expanded)
		{
			return;
		}
		String current = progress.getCurrentStepId();
		String previewed = progress.getPreviewedStepId();
		// A new preview pages to its entry; ending one leaves the page where it was.
		if (!String.valueOf(previewed).equals(lastPreviewed))
		{
			page = previewed != null ? -1 : lastShownPage;
			lastPreviewed = String.valueOf(previewed);
		}
		Object signature = List.of(progress, page);
		if (signature.equals(shownSignature))
		{
			return;
		}
		shownSignature = signature;
		content.removeAll();

		int currentEntry = entryIndexOf(current);
		int previewEntry = entryIndexOf(previewed);
		currentPage = pageOf(currentEntry);
		anchorPage = pageOf(previewEntry >= 0 ? previewEntry : currentEntry);
		int shownPage = page >= 0 ? page : Math.max(0, anchorPage);
		lastShownPage = shownPage;
		Page p = pages.get(shownPage);

		content.add(header(shownPage, currentEntry));

		JPanel box = PanelSections.box();
		for (int i = 0; i < p.entries.size(); i++)
		{
			if (i > 0)
			{
				box.add(PanelColors.divider());
			}
			int index = p.entries.get(i);
			box.add(entryRow(index, currentEntry, previewEntry));
		}
		// A section's short last page keeps the full height, so Earlier and Later stay under
		// the pointer from one click to the next.
		int height = box.getComponent(0).getPreferredSize().height;
		for (int i = p.entries.size(); i < PAGE_SIZE; i++)
		{
			box.add(PanelColors.divider());
			JPanel blank = new JPanel();
			blank.setOpaque(false);
			blank.setPreferredSize(new Dimension(0, height));
			blank.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
			blank.setAlignmentX(LEFT_ALIGNMENT);
			box.add(blank);
		}
		content.add(box);
		content.add(footer(shownPage, currentPage >= 0 && shownPage != currentPage));
		content.revalidate();
		content.repaint();
	}

	/** The index in {@link #entries} of the entry listing {@code stepId}, or -1. */
	private int entryIndexOf(String stepId)
	{
		return stepId == null ? -1 : entryOfStep.getOrDefault(stepId, -1);
	}

	private int pageOf(int entryIndex)
	{
		for (int i = 0; i < pages.size(); i++)
		{
			if (pages.get(i).entries.contains(entryIndex))
			{
				return i;
			}
		}
		return -1;
	}

	private void show(int newPage)
	{
		// A page the player picks outranks the paging a preview starting or ending implies.
		lastPreviewed = String.valueOf(progress.getPreviewedStepId());
		page = newPage;
		refresh();
	}

	/**
	 * Shows the current entry's page. While a preview holds another page, following the anchor
	 * would stay on the preview, so the current page is pinned instead.
	 */
	private void showCurrent()
	{
		show(anchorPage == currentPage ? -1 : currentPage);
	}

	// ---------------------------------------------------------------
	// Header and footer
	// ---------------------------------------------------------------

	/**
	 * "Section 2.2 ▾", which opens the section menu, and which of the section's entries the
	 * page holds. The header names the section by number; the section menu lists the titles.
	 */
	private JPanel header(int shownPage, int currentEntry)
	{
		Page p = pages.get(shownPage);
		int before = 0;
		int count = 0;
		for (int i = 0; i < pages.size(); i++)
		{
			if (pages.get(i).section.equals(p.section))
			{
				before += i < shownPage ? pages.get(i).entries.size() : 0;
				count += pages.get(i).entries.size();
			}
		}

		String currentSection = currentEntry >= 0 ? entries.get(currentEntry).section : null;
		JLabel title = PanelSections.smallLabel("Section " + p.section + " ▾", PanelColors.HEADER);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		title.setToolTipText("Jump to another section");
		title.addMouseListener(PanelSections.clicks(() ->
			sectionMenu(currentSection).show(title, 0, title.getHeight())));

		String range = count <= p.entries.size() ? count + " entries"
			: (before + 1) + "–" + (before + p.entries.size()) + " of " + count;
		return bar(2, 3, title, null, PanelSections.smallLabel(range, COLOR_STEP));
	}

	/** Earlier and Later through the whole guide, with Current between them when away from it. */
	private JPanel footer(int shownPage, boolean away)
	{
		JLabel current = null;
		if (away)
		{
			current = PanelSections.link("Current", PanelColors.LINK,
				"Back to the page with your current entry", this::showCurrent);
			current.setHorizontalAlignment(SwingConstants.CENTER);
		}
		return bar(4, 0, pageLink("Earlier", shownPage > 0 ? shownPage - 1 : -1, -1), current,
			pageLink("Later", shownPage < pages.size() - 1 ? shownPage + 1 : -1, 1));
	}

	/** A full-width row holding {@code west}, an optional {@code centre} and {@code east}. */
	private static JPanel bar(int top, int bottom, JLabel west, JLabel centre, JLabel east)
	{
		JPanel bar = new JPanel(new BorderLayout(4, 0));
		bar.setOpaque(false);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		bar.setBorder(BorderFactory.createEmptyBorder(top, 2, bottom, 2));
		bar.add(west, BorderLayout.WEST);
		if (centre != null)
		{
			bar.add(centre, BorderLayout.CENTER);
		}
		bar.add(east, BorderLayout.EAST);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
		return bar;
	}

	/**
	 * A link that pages to {@code target}, or a dim label at either end of the guide, with a
	 * painted arrow on the side it pages towards ({@code direction} -1 or 1).
	 */
	private JLabel pageLink(String text, int target, int direction)
	{
		Color colour = target >= 0 ? PanelColors.LINK : COLOR_DISABLED;
		JLabel link = target >= 0 ? PanelSections.link(text, colour, null, () -> show(target))
			: PanelSections.smallLabel(text, colour);
		link.setIcon(new Arrow(colour, direction));
		link.setIconTextGap(3);
		link.setHorizontalTextPosition(direction < 0 ? SwingConstants.TRAILING : SwingConstants.LEADING);
		return link;
	}

	/** How wide a section's name may run in the menu before it is cut, about the sidebar's width. */
	private static final int MENU_TEXT_WIDTH = 170;

	/**
	 * Every section, under a short "Chapter N" heading, each marked with how far the player
	 * is through it: done, started with steps left, or not started.
	 */
	private JPopupMenu sectionMenu(String currentSection)
	{
		JPopupMenu menu = new JPopupMenu();
		FontMetrics metrics = menu.getFontMetrics(FontManager.getRunescapeBoldFont());
		String chapter = null;
		String section = null;
		boolean pastCurrent = false;
		for (int i = 0; i < pages.size(); i++)
		{
			Page p = pages.get(i);
			if (p.section.equals(section))
			{
				continue;
			}
			section = p.section;
			String ch = chapterOf.get(section);
			if (ch != null && !ch.equals(chapter))
			{
				chapter = ch;
				int colon = ch.indexOf(':');
				JMenuItem header = new JMenuItem(colon > 0 ? ch.substring(0, colon) : ch);
				header.setEnabled(false);
				header.setFont(FontManager.getRunescapeBoldFont());
				menu.add(header);
			}

			int done = 0;
			int total = 0;
			for (int pg = i; pg < pages.size() && pages.get(pg).section.equals(section); pg++)
			{
				for (int e : pages.get(pg).entries)
				{
					for (GuideStep s : entries.get(e).steps)
					{
						total++;
						if (progress.isFinished(s.getId()))
						{
							done++;
						}
					}
				}
			}
			boolean here = section.equals(currentSection);
			// Sections ahead of the player's read as not started, whatever was finished early.
			Mark mark = pastCurrent ? Mark.TODO
				: done == total ? Mark.DONE
				: done > 0 ? Mark.PARTIAL
				: Mark.TODO;
			pastCurrent |= here;
			String name = escape(fit(sectionLabel(section), metrics, MENU_TEXT_WIDTH));
			JMenuItem item = new JMenuItem("<html>"
				+ (here ? "<b><font color='#ffffff'>" + name + "</font></b>" : name) + "</html>");
			item.setIcon(mark);
			item.setIconTextGap(6);
			item.setFont(FontManager.getRunescapeSmallFont());
			if (!name.equals(escape(sectionLabel(section))))
			{
				item.setToolTipText(sectionLabel(section));
			}
			int target = i;
			item.addActionListener(e ->
			{
				if (here)
				{
					showCurrent();
				}
				else
				{
					show(target);
				}
			});
			menu.add(item);
		}
		return menu;
	}

	/** {@code text}, cut with an ellipsis to fit {@code width} pixels. */
	private static String fit(String text, FontMetrics metrics, int width)
	{
		if (metrics.stringWidth(text) <= width)
		{
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && metrics.stringWidth(cut + "…") > width)
		{
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut.trim() + "…";
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** The section's own title, which already starts with its number ("2.1: Quests and combat"). */
	private String sectionLabel(String section)
	{
		String title = sectionTitles.get(section);
		return title == null || title.isEmpty() ? section : title;
	}

	// ---------------------------------------------------------------
	// Rows and preview
	// ---------------------------------------------------------------

	private JPanel entryRow(int index, int currentEntry, int previewEntry)
	{
		Entry entry = entries.get(index);
		int done = 0;
		int skippedCount = 0;
		for (GuideStep s : entry.steps)
		{
			if (progress.isSkipped(s.getId()))
			{
				skippedCount++;
			}
			else if (progress.isFinished(s.getId()))
			{
				done++;
			}
		}
		int total = entry.steps.size();
		boolean isCurrent = index == currentEntry;
		boolean ahead = currentEntry >= 0 && index > currentEntry;
		boolean open = index == previewEntry;
		boolean allDone = total > 0 && done + skippedCount == total;

		Mark mark;
		if (isCurrent)
		{
			mark = Mark.CURRENT;
		}
		else if (ahead)
		{
			// Ahead of the player nothing reads as done, even what the account finished early.
			mark = Mark.TODO;
		}
		else if (allDone && done == 0)
		{
			mark = Mark.SKIPPED;
		}
		else if (allDone)
		{
			mark = Mark.DONE;
		}
		else if (done + skippedCount > 0)
		{
			mark = Mark.PARTIAL;
		}
		else
		{
			mark = Mark.TODO;
		}

		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(isCurrent || open);
		row.setBackground(open ? COLOR_PREVIEW_BG : COLOR_CURRENT_BG);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// Clicking the player's own entry, or the one already previewed, ends the preview. The
		// panel redraws the navigator once the engine has the preview.
		row.addMouseListener(PanelSections.clicks(() ->
			panel.previewStep(isCurrent || open || entry.steps.isEmpty()
				? null : entry.steps.get(0).getId())));

		JLabel markLabel = new JLabel(mark);
		Dimension markSize = new Dimension(13, 14);
		markLabel.setPreferredSize(markSize);
		markLabel.setMinimumSize(markSize);
		row.add(markLabel, BorderLayout.WEST);

		JLabel summary = PanelSections.smallLabel(summaries.getOrDefault(entry.id, ""),
			isCurrent ? COLOR_CURRENT : COLOR_SUMMARY);
		// Clipped with an ellipsis by the row's width.
		summary.setPreferredSize(new Dimension(0, summary.getPreferredSize().height));
		row.add(summary, BorderLayout.CENTER);

		// The entry's number, which the entry block's "Entry 2.2.3" heading matches; an unfinished
		// entry behind the player shows how many steps are left instead, in the half mark's colour.
		boolean unfinishedBehind = !isCurrent && !ahead && !allDone && done + skippedCount > 0;
		String right = unfinishedBehind ? (total - done - skippedCount) + " left" : entry.id;
		JLabel tail = PanelSections.smallLabel(right,
			isCurrent ? COLOR_CURRENT : unfinishedBehind ? COLOR_PARTIAL : COLOR_STEP);
		row.add(tail, BorderLayout.EAST);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}
}
