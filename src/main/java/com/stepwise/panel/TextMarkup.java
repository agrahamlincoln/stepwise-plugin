package com.stepwise.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Inline markup helpers for the Stepwise UI.
 * <p>
 * Supported markup: {@code **bold**}, {@code *italic*} / {@code _italic_},
 * and {@code ~~strikethrough~~}. Markers may nest. Unclosed markers render
 * literally.
 * <p>
 * {@link #parse(String)} stays Swing-free so Graphics2D overlays
 * ({@link #stripMarkup(String)}) and JTextPane documents can share it.
 */
public final class TextMarkup
{
	private TextMarkup()
	{
	}

	/**
	 * A run of text with its resolved inline styles. Styles are flattened from
	 * the (possibly nested) markup, so a span inside {@code **_..._**} carries
	 * both {@code bold} and {@code italic}.
	 */
	public static final class Span
	{
		public final String text;
		public final boolean bold;
		public final boolean italic;
		public final boolean strike;

		public Span(String text, boolean bold, boolean italic, boolean strike)
		{
			this.text = text;
			this.bold = bold;
			this.italic = italic;
			this.strike = strike;
		}
	}

	/**
	 * Tokenizes inline markup into a flat list of styled spans.
	 * <p>
	 * An opening marker counts only when a matching close exists later in the
	 * range. Otherwise it is emitted literally. All other characters, including
	 * {@code \n}, are preserved.
	 *
	 * @param text input markup (may be {@code null})
	 * @return styled spans in document order; empty for {@code null}/empty input
	 */
	public static List<Span> parse(String text)
	{
		if (text == null || text.isEmpty())
		{
			return Collections.emptyList();
		}
		List<Span> spans = new ArrayList<>();
		parseRange(text, 0, text.length(), false, false, false, new StringBuilder(), spans);
		return spans;
	}

	/**
	 * Parses {@code [start, end)} of {@code text} into {@code out}. The style
	 * flags hold the formatting in effect for this range.
	 */
	private static void parseRange(String text, int start, int end,
		boolean bold, boolean italic, boolean strike,
		StringBuilder buf, List<Span> out)
	{
		int i = start;
		while (i < end)
		{
			// Check "**" before "*" so bold wins over two italic toggles.
			int boldClose = !bold ? findClose(text, i, end, "**") : -1;
			if (boldClose >= 0)
			{
				flush(buf, out, bold, italic, strike);
				parseRange(text, i + 2, boldClose, true, italic, strike, buf, out);
				i = boldClose + 2;
				continue;
			}

			int strikeClose = !strike ? findClose(text, i, end, "~~") : -1;
			if (strikeClose >= 0)
			{
				flush(buf, out, bold, italic, strike);
				parseRange(text, i + 2, strikeClose, bold, italic, true, buf, out);
				i = strikeClose + 2;
				continue;
			}

			char c = text.charAt(i);
			if (!italic && (c == '*' || c == '_'))
			{
				int italicClose = findClose(text, i, end, String.valueOf(c));
				if (italicClose >= 0)
				{
					flush(buf, out, bold, italic, strike);
					parseRange(text, i + 1, italicClose, bold, true, strike, buf, out);
					i = italicClose + 1;
					continue;
				}
			}

			buf.append(c);
			i++;
		}
		flush(buf, out, bold, italic, strike);
	}

	/**
	 * Returns the index of the closing {@code marker} for the opener at
	 * {@code openStart}, or {@code -1} when there is none or the content between
	 * the markers is empty.
	 */
	private static int findClose(String text, int openStart, int end, String marker)
	{
		int len = marker.length();
		if (openStart + len > end || !text.startsWith(marker, openStart))
		{
			return -1;
		}
		int contentStart = openStart + len;
		for (int j = contentStart; j + len <= end; j++)
		{
			if (text.startsWith(marker, j))
			{
				return j > contentStart ? j : -1;
			}
		}
		return -1;
	}

	private static void flush(StringBuilder buf, List<Span> out,
		boolean bold, boolean italic, boolean strike)
	{
		if (buf.length() > 0)
		{
			out.add(new Span(buf.toString(), bold, italic, strike));
			buf.setLength(0);
		}
	}

	/**
	 * Strips inline markup and reduces each {@code [label](url)} link to its label, returning
	 * the visible text for contexts that cannot render formatting, such as Graphics2D overlays.
	 * Markup inside a label is stripped too.
	 *
	 * @param text input markup (may be {@code null})
	 * @return the text with markers removed and links reduced to their labels; unchanged when
	 *         it has neither
	 */
	public static String stripMarkup(String text)
	{
		if (text == null || text.isEmpty())
		{
			return text;
		}
		// Links first, so underscores in a URL are not read as italic markers. A label is still
		// parsed as markup.
		text = MarkdownLinks.LINK.matcher(text).replaceAll("$1");
		if (text.indexOf('*') < 0 && text.indexOf('_') < 0 && text.indexOf('~') < 0)
		{
			return text;
		}
		StringBuilder sb = new StringBuilder(text.length());
		for (Span s : parse(text))
		{
			sb.append(s.text);
		}
		return sb.toString();
	}

	/**
	 * A step instruction as one line of plain text, for the game overlay and notifications:
	 * {@link #stripMarkup} without the trailing links, which only the side panel can open.
	 *
	 * @param instruction the instruction (may be {@code null})
	 * @return the plain text; empty for {@code null}
	 */
	public static String plainInstruction(String instruction)
	{
		return stripMarkup(MarkdownLinks.splitTrailing(instruction).body);
	}
}
