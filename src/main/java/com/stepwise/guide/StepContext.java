package com.stepwise.guide;

import lombok.Value;

import java.util.List;

/**
 * A step's chapter, section and entry context. Built at load time; see
 * {@link StepwiseGuide#stepContextMap()}.
 */
@Value
public class StepContext {
    String chapterTitle;
    String sectionTitle;
    /** The ID of the entry that contains this step. */
    String entryId;
    /** The original text from the entry this step belongs to. */
    String originalText;
    /**
     * The time the guide states for this entry alone, in minutes, or null when it states
     * none. A time stated for several entries together is left out, since the guide does not
     * say how it splits between them.
     */
    Double entryDurationMinutes;
    /**
     * The items-needed list of the entry this step belongs to, or null when the guide lists
     * none. Every step of the entry shares one list, which describes the whole entry.
     */
    List<ItemNeeded> itemsNeeded;
}
