package com.stepwise.sailing;

import net.runelite.api.gameval.VarbitID;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
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

    // Facilities occupy position-indexed hotspots. The innoculation station (value 1) has a
    // fixed hotspot: 5 on a skiff, 9 on a sloop. The keg (value 9) can be built in any deck
    // hotspot, 0 through 12.

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

    /** The hotspot value the innoculation station reports once built. */
    public static final int FACILITY_BUILT = 1;
    /** The hotspot value the keg facility reports once built. */
    public static final int KEG_VALUE = 9;

    /** The game spells it with a double n, and so does every label Stepwise shows. */
    public static final String INOCULATION_STATION = "innoculation station";
    private static final String KEG = "keg";

    /** Number of boat slots the game keeps; every table above has one entry per slot. */
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
        if (boatType != 1 && boatType != 2) {
            return false;
        }
        return facilityBuiltValue(facility) > 0;
    }

    /**
     * The hotspot value reported when the named facility is built, or -1 for a facility
     * Stepwise cannot read.
     */
    public static int facilityBuiltValue(String facility) {
        String canonical = canonicalFacilityName(facility);
        if (INOCULATION_STATION.equals(canonical)) {
            return FACILITY_BUILT;
        }
        if (KEG.equals(canonical)) {
            return KEG_VALUE;
        }
        return -1;
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
     * The hotspot varbit holding a facility on the given boat type in a 0-based slot.
     *
     * @return the varbit id, or -1 when the facility has no fixed hotspot on that boat.
     *         The keg can go in any hotspot, so it always reads -1 here; use {@link #facilityIds}.
     */
    public static int facilityVarbit(String facility, int boatType, int slot) {
        int hotspot = fixedHotspot(facility, boatType);
        return hotspot >= 0 ? ALL_HOTSPOTS[slot][hotspot] : -1;
    }

    /**
     * Every slot's hotspot varbit for a facility on the given boat type, or an empty set when
     * Stepwise cannot read the facility. For the keg, which can go in any hotspot, returns all 13
     * hotspot varbits of every slot.
     */
    public static Set<Integer> facilityIds(String facility, int boatType) {
        boolean keg = KEG.equals(canonicalFacilityName(facility)) && (boatType == 1 || boatType == 2);
        int hotspot = fixedHotspot(facility, boatType);
        Set<Integer> ids = new LinkedHashSet<>();
        for (int slot = 0; slot < SLOTS && (keg || hotspot >= 0); slot++) {
            if (keg) {
                addAll(ids, ALL_HOTSPOTS[slot]);
            } else {
                ids.add(ALL_HOTSPOTS[slot][hotspot]);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * The hotspot index a facility always occupies on a boat type, or -1 when it has none.
     * The keg has no fixed hotspot, so facilityIds answers it from every hotspot instead.
     */
    private static int fixedHotspot(String facility, int boatType) {
        if (!INOCULATION_STATION.equals(canonicalFacilityName(facility))) {
            return -1;
        }
        return boatType == 1 ? 5 : boatType == 2 ? 9 : -1;
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
