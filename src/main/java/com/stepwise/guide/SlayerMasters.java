package com.stepwise.guide;

import java.util.Locale;
import java.util.Map;

/**
 * The Slayer master names a guide condition may use, and the game value each one means.
 *
 * <p>The tracker that watches the vars deals only in raw values, so the names live here.
 */
public final class SlayerMasters {

    private SlayerMasters() {
    }

    /** The {@code SLAYER_MASTER} varbit value for Krystilia, who keeps her own task streak. */
    public static final int KRYSTILIA = 7;

    /**
     * Master name as the guide writes it → {@code SLAYER_MASTER} varbit value.
     *
     * <p>The values come from RuneLite's NPC identifiers, whose suffix equals the varbit value
     * ({@code SLAYER_MASTER_3} is Vannaka, {@code SLAYER_MASTER_5_DURADEL} is Duradel,
     * {@code SLAYER_MASTER_7} is Krystilia, and RuneLite's Slayer plugin compares the varbit
     * against {@code 7} for her).
     *
     * <p>A master not listed never latches. Nieve and Steve have no numbered identifier, and
     * the one numbered 6 ({@code SLAYER_MASTER_6}) names no master, so their value is
     * unknown. Konar ({@code SLAYER_MASTER_8}) and Spria
     * ({@code SLAYER_MASTER_9_ACTIVE}) are left out because the guide never names them.
     *
     * <p>A master who stands in for another during or after While Guthix Sleeps shares that
     * master's value.
     */
    private static final Map<String, Integer> BY_NAME = Map.ofEntries(
            Map.entry("turael", 1),
            Map.entry("aya", 1),        // replaces Turael during and after While Guthix Sleeps
            Map.entry("mazchna", 2),
            Map.entry("achtryn", 2),    // stands in for Mazchna during While Guthix Sleeps
            Map.entry("vannaka", 3),
            Map.entry("chaeldar", 4),
            Map.entry("duradel", 5),
            Map.entry("kuradal", 5),    // replaces Duradel after While Guthix Sleeps
            Map.entry("krystilia", KRYSTILIA));

    /**
     * The master varbit value for a guide name, or null for a name not in {@link #BY_NAME}.
     * A condition naming such a master can never be met.
     */
    public static Integer valueFor(String masterName) {
        if (masterName == null) {
            return null;
        }
        return BY_NAME.get(masterName.trim().toLowerCase(Locale.ROOT));
    }
}
