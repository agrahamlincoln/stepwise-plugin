package com.stepwise.guide;

import lombok.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The route's expected GP balance and skill profile at each guide entry, loaded from
 * {@code guide_budget.json}.
 *
 * <p>The data comes from the BRUHsailer companion spreadsheet: the guide authors' plan for one
 * run of the route. A live player who differs from it sees the difference in the route
 * comparison, and may be offered a skip of an earn phase. Nothing here feeds a latch or a
 * completion condition.
 *
 * <p>Only the fields the runtime reads are bound. Gson ignores the file's other fields.
 */
public final class GuideBudget {

    private final List<Entry> entries;
    /** entryId &rarr; position in {@link #entries}. Built in the constructor, not parsed. */
    private final Map<String, Integer> entryIndex;

    public GuideBudget(List<Entry> entries) {
        this.entries = entries != null ? entries : Collections.emptyList();

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < this.entries.size(); i++) {
            index.put(this.entries.get(i).getEntryId(), i);
        }
        this.entryIndex = Collections.unmodifiableMap(index);
    }

    /**
     * Rebuild a Gson-parsed budget so its derived index exists and its collections are
     * unmodifiable. Gson allocates instances without running any constructor, so a freshly
     * parsed {@code GuideBudget} has a null {@link #entryIndex} until it passes through here.
     */
    static GuideBudget freeze(GuideBudget raw) {
        if (raw == null) {
            return null;
        }
        return new GuideBudget(
                raw.entries != null ? Collections.unmodifiableList(raw.entries) : Collections.emptyList());
    }

    /** Ledger rows in guide order. */
    public List<Entry> getEntries() {
        return entries;
    }

    /** Position of {@code entryId} in guide order, or {@code -1} when the ledger has no row for it. */
    public int indexOf(String entryId) {
        Integer idx = entryIndex.get(entryId);
        return idx != null ? idx : -1;
    }

    /** The ledger row at {@code index}, or null when the index is out of range. */
    public Entry entryAt(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /** One entry's row in the ledger. */
    @Value
    public static class Entry {
        String entryId;
        /** Balance the route expects on arriving at this entry. */
        long gpStart;
        /** The balance the route expects on leaving, less {@link #gpStart}: positive for an earn. */
        long gpChange;
        /** XP the route earns during this entry, by skill name. Absent from the JSON when empty. */
        Map<String, Long> xpGained;
        /** Base levels the route expects once this entry is done, by skill name. */
        Map<String, Integer> levelsAfter;
    }
}
