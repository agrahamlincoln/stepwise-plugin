package com.stepwise.sailing;

import net.runelite.api.gameval.VarbitID;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The game's per-slot varbits for the player's boats.
 *
 * <p>Each of the five boat slots has its own varbit family: an owned flag, the boat type,
 * and one tier per part. The slot holding a given boat depends on fleet history: the
 * Pandemonium starter raft keeps slot 1 unless destroyed, so the skiff usually lands in
 * slot 2, but not always. Guide data therefore names a boat by TYPE and the engine finds
 * the slot.
 *
 * <p>The resolver evaluates {@code boatOwned}/{@code boatComponent} on these ids, and the
 * login scan must claim them so a boat bought before this session is seen.
 *
 * <p>The tables list RuneLite's named constant for each slot.
 */
public final class BoatVarbits {
    /** 1 when the slot holds a boat the player owns. */
    private static final int[] OWNED = {
        VarbitID.SAILING_BOAT_1_OWNED, VarbitID.SAILING_BOAT_2_OWNED, VarbitID.SAILING_BOAT_3_OWNED,
        VarbitID.SAILING_BOAT_4_OWNED, VarbitID.SAILING_BOAT_5_OWNED,
    };
    /** The boat type in the slot: raft 0, skiff 1, sloop 2. See {@link #typeValue}. */
    private static final int[] TYPE = {
        VarbitID.SAILING_BOAT_1_TYPE, VarbitID.SAILING_BOAT_2_TYPE, VarbitID.SAILING_BOAT_3_TYPE,
        VarbitID.SAILING_BOAT_4_TYPE, VarbitID.SAILING_BOAT_5_TYPE,
    };
    /** Keel tier, metal numbering: bronze 0 through dragon 6. */
    private static final int[] KEEL = {
        VarbitID.SAILING_BOAT_1_KEEL, VarbitID.SAILING_BOAT_2_KEEL, VarbitID.SAILING_BOAT_3_KEEL,
        VarbitID.SAILING_BOAT_4_KEEL, VarbitID.SAILING_BOAT_5_KEEL,
    };
    /** Hull tier, wood numbering: wooden 0 through rosewood 6. */
    private static final int[] HULL = {
        VarbitID.SAILING_BOAT_1_HULL, VarbitID.SAILING_BOAT_2_HULL, VarbitID.SAILING_BOAT_3_HULL,
        VarbitID.SAILING_BOAT_4_HULL, VarbitID.SAILING_BOAT_5_HULL,
    };
    /** Mast-and-sails tier, wood numbering: wooden 0 through rosewood 6. */
    private static final int[] SAIL = {
        VarbitID.SAILING_BOAT_1_SAIL, VarbitID.SAILING_BOAT_2_SAIL, VarbitID.SAILING_BOAT_3_SAIL,
        VarbitID.SAILING_BOAT_4_SAIL, VarbitID.SAILING_BOAT_5_SAIL,
    };
    /** Helm tier, metal numbering: bronze 0 through dragon 6. */
    private static final int[] STEERING = {
        VarbitID.SAILING_BOAT_1_STEERING, VarbitID.SAILING_BOAT_2_STEERING, VarbitID.SAILING_BOAT_3_STEERING,
        VarbitID.SAILING_BOAT_4_STEERING, VarbitID.SAILING_BOAT_5_STEERING,
    };
    /** 1 once the eternal brazier is built. */
    private static final int[] BRAZIER = {
        VarbitID.SAILING_BOAT_1_BRAZIER, VarbitID.SAILING_BOAT_2_BRAZIER, VarbitID.SAILING_BOAT_3_BRAZIER,
        VarbitID.SAILING_BOAT_4_BRAZIER, VarbitID.SAILING_BOAT_5_BRAZIER,
    };

    // Facilities occupy position-indexed hotspots. A hotspot's varbit holds the 1-based position
    // of the built facility in that hotspot's option list, and 0 when nothing is built, so one
    // facility reads a different value at each hotspot that offers it.

    /** All 13 facility hotspots per boat slot (slot 0..4 = boats 1..5). */
    private static final int[][] ALL_HOTSPOTS = {
        {
            VarbitID.SAILING_BOAT_1_HOTSPOT_0, VarbitID.SAILING_BOAT_1_HOTSPOT_1, VarbitID.SAILING_BOAT_1_HOTSPOT_2,
            VarbitID.SAILING_BOAT_1_HOTSPOT_3, VarbitID.SAILING_BOAT_1_HOTSPOT_4, VarbitID.SAILING_BOAT_1_HOTSPOT_5,
            VarbitID.SAILING_BOAT_1_HOTSPOT_6, VarbitID.SAILING_BOAT_1_HOTSPOT_7, VarbitID.SAILING_BOAT_1_HOTSPOT_8,
            VarbitID.SAILING_BOAT_1_HOTSPOT_9, VarbitID.SAILING_BOAT_1_HOTSPOT_10, VarbitID.SAILING_BOAT_1_HOTSPOT_11,
            VarbitID.SAILING_BOAT_1_HOTSPOT_12,
        },
        {
            VarbitID.SAILING_BOAT_2_HOTSPOT_0, VarbitID.SAILING_BOAT_2_HOTSPOT_1, VarbitID.SAILING_BOAT_2_HOTSPOT_2,
            VarbitID.SAILING_BOAT_2_HOTSPOT_3, VarbitID.SAILING_BOAT_2_HOTSPOT_4, VarbitID.SAILING_BOAT_2_HOTSPOT_5,
            VarbitID.SAILING_BOAT_2_HOTSPOT_6, VarbitID.SAILING_BOAT_2_HOTSPOT_7, VarbitID.SAILING_BOAT_2_HOTSPOT_8,
            VarbitID.SAILING_BOAT_2_HOTSPOT_9, VarbitID.SAILING_BOAT_2_HOTSPOT_10, VarbitID.SAILING_BOAT_2_HOTSPOT_11,
            VarbitID.SAILING_BOAT_2_HOTSPOT_12,
        },
        {
            VarbitID.SAILING_BOAT_3_HOTSPOT_0, VarbitID.SAILING_BOAT_3_HOTSPOT_1, VarbitID.SAILING_BOAT_3_HOTSPOT_2,
            VarbitID.SAILING_BOAT_3_HOTSPOT_3, VarbitID.SAILING_BOAT_3_HOTSPOT_4, VarbitID.SAILING_BOAT_3_HOTSPOT_5,
            VarbitID.SAILING_BOAT_3_HOTSPOT_6, VarbitID.SAILING_BOAT_3_HOTSPOT_7, VarbitID.SAILING_BOAT_3_HOTSPOT_8,
            VarbitID.SAILING_BOAT_3_HOTSPOT_9, VarbitID.SAILING_BOAT_3_HOTSPOT_10, VarbitID.SAILING_BOAT_3_HOTSPOT_11,
            VarbitID.SAILING_BOAT_3_HOTSPOT_12,
        },
        {
            VarbitID.SAILING_BOAT_4_HOTSPOT_0, VarbitID.SAILING_BOAT_4_HOTSPOT_1, VarbitID.SAILING_BOAT_4_HOTSPOT_2,
            VarbitID.SAILING_BOAT_4_HOTSPOT_3, VarbitID.SAILING_BOAT_4_HOTSPOT_4, VarbitID.SAILING_BOAT_4_HOTSPOT_5,
            VarbitID.SAILING_BOAT_4_HOTSPOT_6, VarbitID.SAILING_BOAT_4_HOTSPOT_7, VarbitID.SAILING_BOAT_4_HOTSPOT_8,
            VarbitID.SAILING_BOAT_4_HOTSPOT_9, VarbitID.SAILING_BOAT_4_HOTSPOT_10, VarbitID.SAILING_BOAT_4_HOTSPOT_11,
            VarbitID.SAILING_BOAT_4_HOTSPOT_12,
        },
        {
            VarbitID.SAILING_BOAT_5_HOTSPOT_0, VarbitID.SAILING_BOAT_5_HOTSPOT_1, VarbitID.SAILING_BOAT_5_HOTSPOT_2,
            VarbitID.SAILING_BOAT_5_HOTSPOT_3, VarbitID.SAILING_BOAT_5_HOTSPOT_4, VarbitID.SAILING_BOAT_5_HOTSPOT_5,
            VarbitID.SAILING_BOAT_5_HOTSPOT_6, VarbitID.SAILING_BOAT_5_HOTSPOT_7, VarbitID.SAILING_BOAT_5_HOTSPOT_8,
            VarbitID.SAILING_BOAT_5_HOTSPOT_9, VarbitID.SAILING_BOAT_5_HOTSPOT_10, VarbitID.SAILING_BOAT_5_HOTSPOT_11,
            VarbitID.SAILING_BOAT_5_HOTSPOT_12,
        },
    };

    /** The game spells it with a double n, and so does every label Stepwise shows. */
    private static final String INOCULATION_STATION = "innoculation station";
    private static final String KEG = "keg";
    private static final String CRYSTAL_EXTRACTOR = "crystal extractor";
    private static final String WIND_CATCHER = "wind catcher";
    /** The wind catcher's variant: a boat holds one or the other, never both. */
    private static final String GALE_CATCHER = "gale catcher";

    /**
     * The value each hotspot reports when the facility is built there, indexed by hotspot, with
     * 0 where the facility cannot go; one row per boat type: skiff first, then sloop. The values
     * are the facility's 1-based position in each hotspot's option list in the client's
     * sailing_boat_hotspot table. A facility missing here is never read.
     */
    private static final Map<String, int[][]> FACILITY_VALUES = Map.of(
            INOCULATION_STATION, new int[][] {
                {0, 0, 0, 0, 0, 1},
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
            },
            KEG, new int[][] {
                {0, 9, 5},
                {0, 2, 2, 9, 9, 5, 5},
            },
            CRYSTAL_EXTRACTOR, new int[][] {
                {8, 17, 13},
                {8, 10, 10, 17, 17, 13, 13},
            },
            WIND_CATCHER, new int[][] {
                {2, 11, 7},
                {2, 4, 4, 11, 11, 7, 7},
            },
            GALE_CATCHER, new int[][] {
                {3, 12, 8},
                {3, 5, 5, 12, 12, 8, 8},
            });

    /** Number of boat slots the game keeps; every per-slot table above has one entry per slot. */
    public static final int SLOTS = OWNED.length;

    private BoatVarbits() {
    }

    /** The owned-flag varbit for a 0-based slot. */
    public static int owned(int slot) {
        return OWNED[slot];
    }

    /** The boat-type varbit for a 0-based slot. */
    public static int type(int slot) {
        return TYPE[slot];
    }

    /** The 0-based slot whose owned flag a varbit is, or -1 when the varbit is not one. */
    public static int slotOfOwnedVarbit(int varbitId) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (OWNED[slot] == varbitId) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * The tier varbit of a named part in a 0-based slot.
     *
     * @return the varbit id, or -1 for an unknown component name
     */
    public static int componentVarbit(String component, int slot) {
        int[] table = componentTable(component);
        return table != null ? table[slot] : -1;
    }

    /** Every slot's owned and type varbits: what finding a boat by type has to read. */
    public static Set<Integer> ownershipIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        addAll(ids, OWNED);
        addAll(ids, TYPE);
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Every slot's tier varbit for a named part, or an empty set for an unknown name.
     */
    public static Set<Integer> componentIds(String component) {
        int[] table = componentTable(component);
        if (table == null) {
            return Collections.emptySet();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        addAll(ids, table);
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Maps a boat type name from guide data to the value the per-slot TYPE varbit reports.
     *
     * @param boatType "raft", "skiff" or "sloop" (case-insensitive)
     * @return the TYPE varbit value, or -1 for an unknown name
     */
    public static int typeValue(String boatType) {
        if (boatType == null) {
            return -1;
        }
        switch (boatType.toLowerCase(Locale.ROOT)) {
            case "raft":
                return 0;
            case "skiff":
                return 1;
            case "sloop":
                return 2;
            default:
                return -1;
        }
    }

    /** Whether guide data's component name maps to a part the game tracks. */
    public static boolean isComponent(String component) {
        return componentTable(component) != null;
    }

    /**
     * Normalises a facility name from guide data to the lower-case spelling the engine keys on.
     * Both spellings, "inoculation station" and the game's "innoculation station", map to the
     * game's double-n key.
     *
     * @return the canonical name, or null when guide data supplied no usable name
     */
    public static String canonicalFacilityName(String facility) {
        if (facility == null || facility.isBlank()) {
            return null;
        }
        String name = facility.trim().toLowerCase(Locale.ROOT);
        return name.equals("inoculation station") ? INOCULATION_STATION : name;
    }

    /**
     * Whether Stepwise can read the facility's built state on the given boat type. A facility
     * it cannot read never latches and claims nothing at login.
     */
    public static boolean isFacility(String facility, int boatType) {
        return facilityValues(facility, boatType) != null;
    }

    /**
     * The value the hotspot at {@code hotspot} reports when the facility is built there, or 0
     * when the facility cannot go there or Stepwise cannot read it.
     */
    public static int facilityValueAt(String facility, int boatType, int hotspot) {
        int[] values = facilityValues(facility, boatType);
        return values != null && hotspot >= 0 && hotspot < values.length ? values[hotspot] : 0;
    }

    /** The per-hotspot built values for a facility on a boat type, or null when unreadable. */
    private static int[] facilityValues(String facility, int boatType) {
        String name = canonicalFacilityName(facility);
        int[][] rows = name == null ? null : FACILITY_VALUES.get(name);
        if (rows == null || (boatType != 1 && boatType != 2)) {
            return null;
        }
        return rows[boatType - 1];
    }

    /**
     * All facility hotspot varbits for a 0-based slot (0..4).
     */
    public static int[] hotspotsForSlot(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return new int[0];
        }
        return ALL_HOTSPOTS[slot];
    }

    /**
     * Every slot's varbit for each hotspot that can hold the facility on the given boat type, or
     * an empty set when Stepwise cannot read the facility.
     */
    public static Set<Integer> facilityIds(String facility, int boatType) {
        int[] values = facilityValues(facility, boatType);
        Set<Integer> ids = new LinkedHashSet<>();
        for (int slot = 0; values != null && slot < SLOTS; slot++) {
            for (int hotspot = 0; hotspot < values.length; hotspot++) {
                if (values[hotspot] > 0) {
                    ids.add(ALL_HOTSPOTS[slot][hotspot]);
                }
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private static int[] componentTable(String component) {
        if (component == null) {
            return null;
        }
        switch (component.toLowerCase(Locale.ROOT)) {
            case "keel":
                return KEEL;
            case "hull":
                return HULL;
            case "sail":
                return SAIL;
            case "steering":
                return STEERING;
            case "brazier":
                return BRAZIER;
            default:
                return null;
        }
    }

    private static void addAll(Set<Integer> into, int[] ids) {
        for (int id : ids) {
            into.add(id);
        }
    }
}
