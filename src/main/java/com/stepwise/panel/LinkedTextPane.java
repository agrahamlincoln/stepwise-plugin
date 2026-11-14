package com.stepwise.panel;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.ToolTipManager;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.BoxView;
import javax.swing.text.ComponentView;
import javax.swing.text.IconView;
import javax.swing.text.LabelView;
import javax.swing.text.ParagraphView;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import net.runelite.client.util.LinkBrowser;

/**
 * Wrapping panel text with inline {@code [label](url)} links: each link reads as its label, in
 * {@link PanelColors#LINK} and underlined, where the source put it in the sentence. A click on a
 * label opens it, the cursor is a hand over one, and its tooltip is the URL.
 *
 * <p>Shared by the step card's guidance and the entry block's source text. Not a JTextArea: a
 * JTextArea has one foreground colour, and a Highlighter painter draws beneath the glyphs, so a
 * label could be underlined there but never coloured; and guidance needs {@link TextMarkup}
 * bold and italic, which only a styled document can show. Not HTML either: a JLabel's CSS
 * widths do not match the panel's pixel width.
 *
 * <p>A JTextPane tracks its parent's width only as the direct view of a JScrollPane viewport.
 * Inside a BoxLayout it would take its one-line preferred width and overflow instead of
 * wrapping; clamping {@link #getMaximumSize()} to the laid-out width keeps it at the
 * container's width, so it wraps like a JTextArea.
 *
 * <p>Like every panel text component it is neither editable nor focusable, so nothing can be
 * selected. A refresh that does not change the text must not call {@link #setMarkdown}: the
 * document rebuild re-wraps the text (see {@link RebuildGuard}).
 */
class LinkedTextPane extends JTextPane
{
	private static final Color COLOR_LINK = PanelColors.LINK;
	private static final Cursor HAND = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
	private static final Cursor DEFAULT = Cursor.getDefaultCursor();

	private final Color colour;
	private final Font font;
	private MarkdownLinks.Parsed parsed = MarkdownLinks.parse(null, false);

	/**
	 * @param colour the colour of the text around the links
	 * @param font the text's font; links use it too
	 */
	LinkedTextPane(Color colour, Font font)
	{
		this.colour = colour;
		this.font = font;
		// First: installing a kit replaces the document.
		setEditorKit(new WrappingKit());
		setEditable(false);
		setFocusable(false);
		setOpaque(false);
		setForeground(colour);
		setFont(font);
		setAlignmentX(LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder());
		setCaret(new StepCardPanel.NonScrollingCaret());
		// Registered for getToolTipText(MouseEvent); a fixed tooltip would show off the links too.
		ToolTipManager.sharedInstance().registerComponent(this);

		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				String url = urlAt(e.getPoint());
				if (url != null)
				{
					LinkBrowser.browse(url);
				}
			}

			@Override
			public void mouseMoved(MouseEvent e)
			{
				Cursor wanted = urlAt(e.getPoint()) != null ? HAND : DEFAULT;
				if (getCursor() != wanted)
				{
					setCursor(wanted);
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	/**
	 * Replaces the text. Call only when the text changes, not on every refresh.
	 *
	 * @param markdown the text, with inline {@code [label](url)} links; null reads as empty
	 * @param inlineMarkup whether {@code **bold**}, {@code *italic*} and {@code ~~strike~~}
	 *     markers are applied. False for source text, whose asterisks are literal.
	 */
	void setMarkdown(String markdown, boolean inlineMarkup)
	{
		parsed = MarkdownLinks.parse(markdown, inlineMarkup);
		StyledDocument doc = getStyledDocument();
		try
		{
			doc.remove(0, doc.getLength());
			for (MarkdownLinks.Run run : parsed.runs)
			{
				doc.insertString(doc.getLength(), run.text, style(run));
			}
		}
		catch (BadLocationException e)
		{
			// Unreachable: every offset used is 0 or the document's own length.
			throw new IllegalStateException(e);
		}
		setCursor(DEFAULT);
	}

	/** The text as it reads, links shown as their labels. */
	String plainText()
	{
		return parsed.text;
	}

	/**
	 * The URL of the label under a point, or null. A point past the end of a line, or in the
	 * gap beside a label, is not on it: {@code viewToModel2D} answers with the nearest offset
	 * however far away the point is, so the character's own box is checked as well.
	 *
	 * @param point a point in this component's coordinates
	 * @return the label's URL, or null when the point is not on a label
	 */
	String urlAt(Point point)
	{
		int offset = viewToModel2D(point);
		if (offset < 0)
		{
			return null;
		}
		// viewToModel2D rounds to the nearer caret position, so the character under the point
		// is either the one after that position or the one before it.
		for (int candidate = offset; candidate >= offset - 1 && candidate >= 0; candidate--)
		{
			String url = parsed.urlAt(candidate);
			if (url != null && characterContains(candidate, point))
			{
				return url;
			}
		}
		return null;
	}

	@Override
	public String getToolTipText(MouseEvent event)
	{
		return urlAt(event.getPoint());
	}

	@Override
	public Dimension getMaximumSize()
	{
		// The preferred height already reflects wrapping at this width.
		int width = getWidth();
		if (width <= 0)
		{
			return super.getMaximumSize();
		}
		return new Dimension(width, getPreferredSize().height);
	}

	/** Whether the point falls inside the box the character at {@code offset} is drawn in. */
	private boolean characterContains(int offset, Point point)
	{
		try
		{
			Rectangle2D start = modelToView2D(offset);
			Rectangle2D end = modelToView2D(offset + 1);
			if (start == null || end == null
				|| point.y < start.getY() || point.y >= start.getMaxY())
			{
				return false;
			}
			// When the line wraps after this character the next offset sits on the line below,
			// so the box runs to the line's end.
			double right = end.getY() == start.getY() ? end.getX() : getWidth();
			return point.x >= start.getX() && point.x < right;
		}
		catch (BadLocationException e)
		{
			return false;
		}
	}

	/**
	 * A styled kit whose text runs report no minimum width. A BoxView lays each paragraph out
	 * at no less than the paragraph's minimum span, and the JDK's GlyphView reports a minimum
	 * that grows with the run, so a long run can lay out, and draw, wider than the pane and past
	 * the panel's edge. With a zero minimum the paragraph flows at the width it is given, and a
	 * word longer than a line breaks inside itself, as it would in a JTextArea.
	 */
	private static final class WrappingKit extends StyledEditorKit
	{
		private static final ViewFactory FACTORY = element ->
		{
			String kind = element.getName();
			if (AbstractDocument.ParagraphElementName.equals(kind))
			{
				return new ParagraphView(element);
			}
			if (AbstractDocument.SectionElementName.equals(kind))
			{
				return new BoxView(element, View.Y_AXIS);
			}
			if (StyleConstants.ComponentElementName.equals(kind))
			{
				return new ComponentView(element);
			}
			if (StyleConstants.IconElementName.equals(kind))
			{
				return new IconView(element);
			}
			return new LabelView(element)
			{
				@Override
				public float getMinimumSpan(int axis)
				{
					return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
				}
			};
		};

		@Override
		public ViewFactory getViewFactory()
		{
			return FACTORY;
		}
	}

	private SimpleAttributeSet style(MarkdownLinks.Run run)
	{
		SimpleAttributeSet style = new SimpleAttributeSet();
		StyleConstants.setFontFamily(style, font.getFamily());
		StyleConstants.setFontSize(style, font.getSize());
		StyleConstants.setForeground(style, run.url != null ? COLOR_LINK : colour);
		StyleConstants.setUnderline(style, run.url != null);
		StyleConstants.setBold(style, run.bold);
		StyleConstants.setItalic(style, run.italic);
		StyleConstants.setStrikeThrough(style, run.strike);
		return style;
	}
}
