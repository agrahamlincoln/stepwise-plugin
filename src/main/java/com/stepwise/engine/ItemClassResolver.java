package com.stepwise.engine;

import com.google.gson.Gson;
import com.stepwise.guide.GuideLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.Reader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves item class names to sets of valid item IDs for hasItem matching.
 *
 * <p>Loaded from {@code /com/stepwise/item_classes.json} in plugin resources. Supports:
 * <ul>
 *   <li><b>Tool classes</b> — any member satisfies (optionally filtered by minTier)</li>
 *   <li><b>Charged items</b> — any variant the class lists satisfies; each class decides
 *       whether its uncharged shell counts</li>
 *   <li><b>Upgrade paths</b> — a base item plus all its upgraded variants satisfy the base check</li>
 * </ul>
 *
 * <p>Resolved ID sets are cached, so repeated evaluations do not allocate. An unknown class
 * resolves to an empty set, and the caller falls back to exact ID matching.
 */
@Slf4j
public final class ItemClassResolver {
    private static final String RESOURCE_PATH = "/com/stepwise/item_classes.json";

    private final Map<String, ToolClass> toolClasses;
    /** Pre-computed valid ID sets for charged items (tier-independent). */
    private final Map<String, Set<Integer>> chargedIdSets = new HashMap<>();
    /** Maps every member ID to the full set of equivalent IDs that satisfy a check for it,
     *  so a lookup works whichever variant the guide data references:
     *  <ul>
     *    <li>upgrade chains: Black mask, Slayer helmet, Slayer helmet (i) share one set</li>
     *    <li>charge variants: a condition on Digsite pendant (1) also matches a full-charge
     *        pendant. An uncharged shell joins only when its class lists it.</li>
     *  </ul> */
    private final Map<Integer, Set<Integer>> idEquivalences = new HashMap<>();
    /** Cached valid ID sets for tool classes, keyed by "className:minTier".
     *  Concurrent because both the client thread and the EDT read it. */
    private final Map<String, Set<Integer>> toolIdCache = new ConcurrentHashMap<>();

    private ItemClassResolver(ItemClassesFile parsed) {
        // Gson leaves a section null when the file writes it as null, and returns null for an
        // empty file; either reads as nothing to resolve.
        ItemClassesFile file = parsed != null ? parsed : new ItemClassesFile();
        this.toolClasses = file.tool_classes != null ? file.tool_classes : Collections.emptyMap();
        Map<String, ChargedItem> chargedItems =
                file.charged_items != null ? file.charged_items : Collections.emptyMap();
        Map<String, UpgradePath> upgradePaths =
                file.upgrade_paths != null ? file.upgrade_paths : Collections.emptyMap();
        for (Map.Entry<String, ChargedItem> entry : chargedItems.entrySet()) {
            chargedIdSets.put(entry.getKey(), Set.copyOf(entry.getValue().getAllValidIds()));
        }

        // The guide names one id while the player may hold another: a condition on "Slayer
        // helmet" (11864) must accept an imbued one such as 25177, and a crafted Digsite
        // pendant (5) must latch a condition catalogued under the (1)-charge id.
        // Each charged class decides in valid_ids whether its uncharged shell counts: a glory
        // lists only charged ids, while any Pharaoh's sceptre satisfies "obtain a sceptre".
        for (UpgradePath path : upgradePaths.values()) {
            Set<Integer> allIds = new HashSet<>();
            allIds.add(path.base.id);
            for (UpgradeMember upgrade : path.upgrades) {
                allIds.add(upgrade.id);
            }
            index(Set.copyOf(allIds));
        }
        chargedIdSets.values().forEach(this::index);

        log.debug("Loaded item classes: {} tool classes, {} charged items, {} upgrade paths",
                toolClasses.size(), chargedIdSets.size(), upgradePaths.size());
    }

    private void index(Set<Integer> equivalentIds) {
        for (int id : equivalentIds) {
            idEquivalences.put(id, equivalentIds);
        }
    }

    /**
     * Load item class data from the plugin JAR resource.
     * Returns an empty resolver if the resource is missing or malformed.
     */
    public static ItemClassResolver loadFromResource(Gson gson) {
        return GuideLoader.readResource(RESOURCE_PATH, reader -> parse(reader, gson),
                () -> new ItemClassResolver(new ItemClassesFile()));
    }

    /** Builds a resolver from item class JSON; a null or empty section resolves nothing. */
    static ItemClassResolver parse(Reader reader, Gson gson) {
        return new ItemClassResolver(gson.fromJson(reader, ItemClassesFile.class));
    }

    /**
     * Resolve a class name to the set of valid item IDs that satisfy it.
     *
     * <p>For tool classes, returns all member IDs at or above the given minTier.
     * For charged items, returns every id the class lists, which leaves out the uncharged
     * shell unless the class counts it.
     *
     * <p>Repeated calls with the same arguments return the same cached set instance.
     *
     * @param className the item class name (e.g. "hammer", "ring_of_dueling")
     * @param minTier   minimum tier for tiered classes, or null for any tier
     * @return set of valid item IDs, empty if class not recognized
     */
    public Set<Integer> resolveValidIds(String className, Integer minTier) {
        Set<Integer> chargedIds = chargedIdSets.get(className);
        if (chargedIds != null) {
            return chargedIds;
        }

        ToolClass tool = toolClasses.get(className);
        if (tool == null) {
            return Collections.emptySet();
        }

        String cacheKey = minTier != null ? className + ":" + minTier : className;
        return toolIdCache.computeIfAbsent(cacheKey, key -> {
            Set<Integer> ids = new HashSet<>();
            for (ToolMember member : tool.members) {
                if (minTier == null || member.tier == null || member.tier >= minTier) {
                    ids.add(member.id);
                }
            }
            return Collections.unmodifiableSet(ids);
        });
    }

    /**
     * Get all item IDs equivalent to the given item: every member of its upgrade chain or
     * its charge-variant set. Lookups work from any member, not just the base item.
     *
     * @param itemId any item ID that may belong to an upgrade chain or charge-variant set
     * @return set containing all equivalent IDs, or null if the item has no equivalence set
     */
    public Set<Integer> resolveEquivalentIds(int itemId) {
        return idEquivalences.get(itemId);
    }

    // --- JSON data classes ---

    /** The resource's top level; Gson binds the snake_case keys by field name. */
    private static class ItemClassesFile {
        Map<String, ToolClass> tool_classes = Collections.emptyMap();
        Map<String, ChargedItem> charged_items = Collections.emptyMap();
        Map<String, UpgradePath> upgrade_paths = Collections.emptyMap();
    }

    private static class ToolClass {
        List<ToolMember> members = Collections.emptyList();
    }

    private static class ToolMember {
        int id;
        Integer tier;
    }

    private static class ChargedItem {
        List<Integer> valid_ids = Collections.emptyList();
        // Trimmed/imbued variants also included in valid_ids resolution
        List<Integer> trimmed_valid_ids;
        List<Integer> imbued_valid_ids;

        /** Get all valid IDs including cosmetic/imbued variants. */
        List<Integer> getAllValidIds() {
            List<Integer> all = new ArrayList<>(valid_ids);
            if (trimmed_valid_ids != null) {
                all.addAll(trimmed_valid_ids);
            }
            if (imbued_valid_ids != null) {
                all.addAll(imbued_valid_ids);
            }
            return all;
        }
    }

    private static class UpgradePath {
        UpgradeMember base;
        List<UpgradeMember> upgrades = Collections.emptyList();
    }

    private static class UpgradeMember {
        int id;
    }
}
