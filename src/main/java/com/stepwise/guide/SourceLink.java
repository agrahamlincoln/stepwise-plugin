package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

/**
 * One hyperlink from the guide's text for an entry, as listed in
 * {@code guide.json}: an example setup, a video, a wiki page.
 *
 * <p>The {@link #label} is the guide's own link text, which already appears inline in the
 * entry's original text. It is sometimes prose ("Example setup here") and sometimes a linked
 * document's title, which can be a bare filename or URL. It is never empty.
 */
@Value
@Builder
public class SourceLink {
    /** The link text as the guide wrote it. Never empty. */
    @SerializedName("label") String label;
    /** Where the link points, verbatim from the guide. */
    @SerializedName("url") String url;
}
