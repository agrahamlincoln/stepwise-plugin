package com.stepwise.panel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline {@code [label](url)} links in guide text: the text as it reads with each link replaced
 * by its label, and where each label sits so a click can find its URL.
 *
 * <p>Swing-free, like {@link TextMarkup}, so the trailing-group split, the parse and the
 * offset-to-URL mapping are unit testable. {@link LinkedText} draws the result: trailing links
 * as rows, the rest inline through {@link LinkedTextPane}.
 *
 * <p>Links are masked before {@link TextMarkup} sees the text. A screenshot label such as
 * {@code F3_Grapple_West.png} or a wiki URL such as {@code .../Gout_tuber/Strategies}
 * would otherwise pair its underscores into italics.
 */
final class MarkdownLinks
{
	/**
	 * A well-formed link: a one-line label with no brackets, then an http(s) URL with no
	 * whitespace and no raw parentheses (the guide data percent-encodes them as {@code %28}/
	 * {@code %29}). Anything else is plain text.
	 */
	static final Pattern LINK = Pattern.compile("\\[([^\\[\\]\\r\\n]+)]\\((https?://[^\\s()]+)\\)");

	/**
	 * First character of the placeholders that stand in for links while {@link TextMarkup}
	 * parses. Private-use code points never occur in guide text, and each placeholder is one
	 * character, so no markup span can split a label.
	 */
	private static final char PLACEHOLDER_BASE = (char) 0xE000;
	private static final int PLACEHOLDER_LIMIT = 0xF8FF - 0xE000;

	private MarkdownLinks()
	{
	}

	/** A run of text in one style. {@link #url} is set only on a link's label. */
	static final class Run
	{
		final String text;
		final boolean bold;
		final boolean italic;
		final boolean strike;
		/** Where the run links to, or null for plain text. */
		final String url;

		Run(String text, boolean bold, boolean italic, boolean strike, String url)
		{
			this.text = text;
			this.bold = bold;
			this.italic = italic;
			this.strike = strike;
			this.url = url;
		}
	}

	/** Where a label sits in the rendered text: {@code [start, end)}. */
	static final class Link
	{
		final int start;
		final int end;
		final String url;

		Link(int start, int end, String url)
		{
			this.start = start;
			this.end = end;
			this.url = url;
		}
	}

	/** Parsed text: the runs to draw, the plain text they spell, and the link ranges in it. */
	static final class Parsed
	{
		final List<Run> runs;
		final String text;
		final List<Link> links;

		private Parsed(List<Run> runs)
		{
			this.runs = runs;
			StringBuilder text = new StringBuilder();
			List<Link> links = new ArrayList<>();
			for (Run run : runs)
			{
				if (run.url != null)
				{
					links.add(new Link(text.length(), text.length() + run.text.length(), run.url));
				}
				text.append(run.text);
			}
			this.text = text.toString();
			this.links = Collections.unmodifiableList(links);
		}

		/**
		 * The URL of the label covering a character.
		 *
		 * @param offset a character offset into {@link #text}
		 * @return the URL, or null when the character is not part of a label
		 */
		String urlAt(int offset)
		{
			for (Link link : links)
			{
				if (offset >= link.start && offset < link.end)
				{
					return link.url;
				}
			}
			return null;
		}
	}

	/** Guide text split into the body and the trailing link group lifted out of it. */
	static final class Split
	{
		/** The text before the trailing group, trailing whitespace removed. May be empty. */
		final String body;
		/** The trailing group's links in order, each {@code {label, url}}. Empty when none. */
		final List<String[]> trailing;

		private Split(String body, List<String[]> trailing)
		{
			this.body = body;
			this.trailing = Collections.unmodifiableList(trailing);
		}
	}

	/**
	 * Splits off the links an author put after the final sentence, which the panel lifts into
	 * rows of their own; every other link stays in the body, to be drawn inline.
	 *
	 * <p>The trailing group is the longest run of links at the very end of the text with
	 * nothing but whitespace after and between them. So "[the setup](url)." stays inline, as
	 * does a link followed by a comma. A link a browser could not open ends the group, so it
	 * stays in the body and reads as plain text there.
	 *
	 * @param markdown the text; null reads as empty
	 * @return the body and the trailing group
	 */
	static Split splitTrailing(String markdown)
	{
		if (markdown == null)
		{
			return new Split("", Collections.emptyList());
		}
		List<int[]> spans = new ArrayList<>();
		List<String[]> links = new ArrayList<>();
		Matcher m = LINK.matcher(markdown);
		while (m.find())
		{
			spans.add(new int[]{m.start(), m.end()});
			links.add(new String[]{m.group(1), m.group(2)});
		}

		// Walk back from the end while each gap holds only whitespace.
		int groupStart = markdown.length();
		int first = links.size();
		for (int i = links.size() - 1; i >= 0; i--)
		{
			if (!markdown.substring(spans.get(i)[1], groupStart).isBlank()
				|| !isBrowsable(links.get(i)[1]))
			{
				break;
			}
			groupStart = spans.get(i)[0];
			first = i;
		}
		return new Split(markdown.substring(0, groupStart).stripTrailing(),
			new ArrayList<>(links.subList(first, links.size())));
	}

	/**
	 * Parses guide text.
	 *
	 * <p>Text that only looks like a link stays as written: an unclosed bracket or parenthesis
	 * does not match, and nor does a link to anything but http or https. A well-formed http(s)
	 * link whose URL does not parse keeps its label as plain text, so the reader loses nothing
	 * and no click can fail.
	 *
	 * @param markdown the text; null reads as empty
	 * @param inlineMarkup whether to apply {@link TextMarkup} bold, italic and strikethrough.
	 *     Off for source text, whose asterisks and underscores are literal.
	 * @return the parsed text
	 */
	static Parsed parse(String markdown, boolean inlineMarkup)
	{
		if (markdown == null || markdown.isEmpty())
		{
			return new Parsed(Collections.emptyList());
		}

		List<String[]> found = new ArrayList<>();
		StringBuilder masked = new StringBuilder(markdown.length());
		Matcher m = LINK.matcher(markdown);
		int last = 0;
		while (m.find() && found.size() < PLACEHOLDER_LIMIT)
		{
			masked.append(markdown, last, m.start());
			masked.append((char) (PLACEHOLDER_BASE + found.size()));
			found.add(new String[]{m.group(1), m.group(2)});
			last = m.end();
		}
		masked.append(markdown, last, markdown.length());

		List<TextMarkup.Span> spans = inlineMarkup
			? TextMarkup.parse(masked.toString())
			: List.of(new TextMarkup.Span(masked.toString(), false, false, false));

		List<Run> runs = new ArrayList<>();
		for (TextMarkup.Span span : spans)
		{
			unmask(span, found, runs);
		}
		return new Parsed(runs);
	}

	/** Splits a span at its placeholders, putting each link's label back in its place. */
	private static void unmask(TextMarkup.Span span, List<String[]> found, List<Run> out)
	{
		String text = span.text;
		int plainStart = 0;
		for (int i = 0; i < text.length(); i++)
		{
			int index = text.charAt(i) - PLACEHOLDER_BASE;
			if (index < 0 || index >= found.size())
			{
				continue;
			}
			if (i > plainStart)
			{
				out.add(new Run(text.substring(plainStart, i), span.bold, span.italic, span.strike, null));
			}
			String label = found.get(index)[0];
			String url = found.get(index)[1];
			out.add(new Run(label, span.bold, span.italic, span.strike, isBrowsable(url) ? url : null));
			plainStart = i + 1;
		}
		if (plainStart < text.length())
		{
			out.add(new Run(text.substring(plainStart), span.bold, span.italic, span.strike, null));
		}
	}

	/**
	 * Whether a click could open this URL. {@link #LINK} already admits only http(s), but a
	 * well-formed link can still hold a character {@code URI.create} rejects ({@code |}, an
	 * opening brace, a quote); {@code LinkBrowser.browse} would then throw on the EDT inside the
	 * click handler, so such a link reads as plain text instead.
	 *
	 * @param url the link target
	 * @return true when the URL parses, names a host, and is http or https
	 */
	static boolean isBrowsable(String url)
	{
		try
		{
			URI uri = new URI(url);
			String scheme = uri.getScheme();
			return uri.getHost() != null
				&& ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
		}
		catch (URISyntaxException e)
		{
			return false;
		}
	}
}
