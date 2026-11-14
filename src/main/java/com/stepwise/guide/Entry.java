package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

@Value
@Builder
public class Entry {
    @SerializedName("id") String id;
    @SerializedName("originalText") String originalText;
    @SerializedName("estimatedDurationMinutes") Double estimatedDurationMinutes;
    /**
     * True when {@link #estimatedDurationMinutes} is this entry's share of a time the guide
     * states for several entries together, rather than a time it states for this entry alone.
     * The guide data omits the key when false.
     */
    @SerializedName("durationEstimated") boolean durationEstimated;
    /**
     * The entry's items-needed list, one {@link ItemNeeded} row per item.
     * Null when the guide lists no items for this entry.
     */
    @SerializedName("itemsNeeded") List<ItemNeeded> itemsNeeded;
    /**
     * The guide's hyperlinks for this entry, in the guide's order. The guide data omits the
     * key when the entry has none, so read it through {@link #getLinks()}.
     */
    @SerializedName("links") List<SourceLink> links;
    @SerializedName("steps") List<GuideStep> steps;

    /**
     * The guide's hyperlinks for this entry.
     *
     * @return the links in the guide's order; empty, never null, when the entry has none
     */
    public List<SourceLink> getLinks() {
        // Gson leaves an absent key null, and the guide data omits the key for an entry without
        // links, so the empty case arrives as null on almost every entry.
        return links == null ? Collections.emptyList() : links;
    }
}
