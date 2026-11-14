package com.stepwise.panel;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * Guide text with its links, for the step card's guidance and the entry block's source text.
 * The author picks how a link shows by where they put it:
 *
 * <ul>
 *   <li>Links that end the text, apart only by whitespace ({@link MarkdownLinks#splitTrailing}),
 *       are lifted into rows of their own beneath it, one {@code ↗ label} per link.</li>
 *   <li>Every other link stays in the sentence, drawn inline by {@link LinkedTextPane}.</li>
 * </ul>
 *
 * <p>Set the text only when it changes ({@link #setMarkdown}): it rebuilds the rows and the
 * document, and a rebuilt wrapping component re-wraps (see {@link RebuildGuard}).
 */
class LinkedText extends JPanel
{
	/** Space above each trailing row. */
	private static final int ROW_GAP = 2;

	private final LinkedTextPane body;

	/**
	 * @param colour the colour of the body text
	 * @param font the body text's font; trailing rows use the small RuneScape font
	 */
	LinkedText(Color colour, Font font)
	{
		PanelSections.column(this);
		body = new LinkedTextPane(colour, font);
		add(body);
	}

	/**
	 * Replaces the text and its links. Call only when the text changes.
	 *
	 * @param markdown the text, with {@code [label](url)} links; null reads as empty
	 * @param inlineMarkup whether {@link TextMarkup} bold, italic and strikethrough apply to the
	 *     body. False for source text, whose asterisks are literal.
	 */
	void setMarkdown(String markdown, boolean inlineMarkup)
	{
		MarkdownLinks.Split split = MarkdownLinks.splitTrailing(markdown);
		body.setMarkdown(split.body, inlineMarkup);
		body.setVisible(!split.body.isEmpty());

		// The body is the first child; everything after it belongs to the trailing rows.
		while (getComponentCount() > 1)
		{
			remove(getComponentCount() - 1);
		}
		for (String[] link : split.trailing)
		{
			add(Box.createVerticalStrut(ROW_GAP));
			JTextArea row = link("↗ " + link[0], link[1]);
			row.setToolTipText(link[1]);
			add(row);
		}
		revalidate();
	}

	// --- Test seams ---

	/** The body text as it reads, inline links shown as their labels. */
	String bodyTextForTest()
	{
		return body.plainText();
	}

	LinkedTextPane bodyForTest()
	{
		return body;
	}

	/** The trailing rows' text, in order. */
	List<String> rowTextsForTest()
	{
		List<String> texts = new ArrayList<>();
		for (int i = 0; i < getComponentCount(); i++)
		{
			if (getComponent(i) instanceof JTextArea)
			{
				texts.add(((JTextArea) getComponent(i)).getText());
			}
		}
		return texts;
	}

	@Override
	public Dimension getMaximumSize()
	{
		// Never taller than its content, so a BoxLayout parent cannot pad it with spare height.
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/** Wrapping link text in small type: a hand cursor over it, and a click opens {@code url}. */
	static JTextArea link(String text, String url)
	{
		JTextArea link = StepCardPanel.textArea(text, PanelColors.LINK,
			FontManager.getRunescapeSmallFont());
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		// LinkBrowser.browse hands a URL to the browser. Its sibling open is for directories
		// (Desktop.open on a File) and fails on a URL wherever xdg-open is missing.
		link.addMouseListener(PanelSections.clicks(() -> LinkBrowser.browse(url)));
		return link;
	}
}
