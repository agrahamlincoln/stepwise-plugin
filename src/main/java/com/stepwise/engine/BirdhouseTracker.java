/*
 * Copyright (c) 2018 Abex
 * Copyright (c) 2018, Daniel Teo <https://github.com/takuyakanbr>
 * Copyright (c) 2026, agrahamlincoln
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.stepwise.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;

/**
 * Tracks the four Fossil Island bird house spaces: their build tier, state, and
 * (for seeded houses) a wall-clock "ready" time. No RuneLite dependency: the plugin
 * reads the varps and player region and passes them in.
 *
 * <p>Bird houses have no "ready" varp, so completion is wall-clock only:
 * {@code readyAt = seededAt + 50min}, as in RuneLite's Time Tracking plugin.
 * {@code seededAt} is stamped when a house is first seen seeded (worst case a full
 * 50 minutes) and refined from the "Seed level is: X/10" dialog.
 */
public class BirdhouseTracker {

    /** 50 minutes, the same for every tier. */
    public static final long FILL_DURATION_SECONDS = 3000;
    /** Seed level shown in the check dialog runs 10 (just seeded) down to 0 (spent). */
    public static final int SEED_LEVEL_MAX = 10;
    public static final long SECONDS_PER_LEVEL = FILL_DURATION_SECONDS / SEED_LEVEL_MAX;

    /** More houses than this emptying in one update counts as stale-varp noise. */
    private static final int RESET_GUARD_THRESHOLD = 2;

    /**
     * The reset guard applies only for this many in-region ticks, long enough for the
     * transmit varps to sync. After that a mass-empty (houses dismantled while unobserved)
     * is accepted, so persisted timers cannot stay seeded forever.
     */
    private static final int RESET_GUARD_SETTLE_TICKS = 3;

    // --- Enums ---

    /**
     * The four bird house spaces on Fossil Island. The game's southern Mushroom Meadow space
     * stands at the entrance to the Tar Swamp, well south of the meadow's mushtree, so the
     * player-facing label names it by the swamp, as the OSRS Wiki does.
     */
    @Getter
    @AllArgsConstructor
    public enum BirdhouseSpace {
        MEADOW_NORTH(1626, "Mushroom Meadow"),
        MEADOW_SOUTH(1627, "Tar Swamp"),
        VALLEY_NORTH(1628, "Verdant Valley, north-east"),
        VALLEY_SOUTH(1629, "Verdant Valley, south-west");

        private final int varpId;
        /** Where the space is, as a player names it. */
        private final String label;

        public static BirdhouseSpace fromVarpId(int varpId) {
            for (BirdhouseSpace s : values()) {
                if (s.varpId == varpId) {
                    return s;
                }
            }
            return null;
        }
    }

    public enum BirdhouseState {
        EMPTY,
        BUILT,
        SEEDED,
        UNKNOWN;

        public static BirdhouseState fromVarpValue(int varp) {
            if (varp < 0 || varp > BirdhouseTier.values().length * 3) {
                return UNKNOWN;
            }
            if (varp == 0) {
                return EMPTY;
            }
            if (varp % 3 == 0) {
                return SEEDED;
            }
            return BUILT;
        }
    }

    /** Build tier of a house, in varp order. The item ID doubles as the InfoBox icon. */
    @Getter
    @AllArgsConstructor
    public enum BirdhouseTier {
        NORMAL(21512),
        OAK(21515),
        WILLOW(21518),
        TEAK(21521),
        MAPLE(22192),
        MAHOGANY(22195),
        YEW(22198),
        MAGIC(22201),
        REDWOOD(22204);

        private final int itemId;

        /** Tier for a varp value, or null if the space is empty / the value is out of range. */
        public static BirdhouseTier fromVarpValue(int varp) {
            int index = (varp - 1) / 3;
            if (varp <= 0 || index >= values().length) {
                return null;
            }
            return values()[index];
        }
    }

    /** Persisted per-house record: the raw varp plus when we believe it was seeded. */
    @Value
    public static class PersistedEntry {
        int varpId;
        int varpValue;
        long seededAtEpochSec;
    }

    /** Live per-house state. seededAt is only meaningful while the house is SEEDED. */
    private static final class Entry {
        final int varpValue;
        // volatile: written on the client thread (update/probe), read on the EDT (TimerPanel).
        volatile long seededAtEpochSec;

        Entry(int varpValue, long seededAtEpochSec) {
            this.varpValue = varpValue;
            this.seededAtEpochSec = seededAtEpochSec;
        }
    }

    /** Concurrent: written on the client thread, read by the timer panel on the EDT. */
    private final Map<BirdhouseSpace, Entry> data = new ConcurrentHashMap<>();

    /** In-region ticks since entering the region — scopes the reset guard. */
    private int inRegionTicks;

    // --- Update ---

    /**
     * Fold in the current varp values. Samples only while the player is in a Fossil
     * Island region, because elsewhere the transmit varps are stale. Mirrors RuneLite's
     * {@code BirdHouseTracker.updateData}, including the reset guard: varps lag a tick on
     * region entry and briefly read 0.
     *
     * @return true if any house changed, so the caller should persist and refresh the UI
     */
    public boolean update(Map<BirdhouseSpace, Integer> varps, boolean inRegion, long nowEpochSec) {
        if (!inRegion) {
            inRegionTicks = 0;
            return false;
        }
        inRegionTicks++;

        Map<BirdhouseSpace, Entry> changes = new EnumMap<>(BirdhouseSpace.class);
        int removalCount = 0;

        for (BirdhouseSpace space : BirdhouseSpace.values()) {
            int varp = varps.getOrDefault(space, 0);
            Entry old = data.get(space);
            int oldVarp = old == null ? -1 : old.varpValue;

            if (varp != oldVarp) {
                changes.put(space, new Entry(varp, nowEpochSec));
            }

            if (varp <= 0 && oldVarp > 0) {
                removalCount++;
            }
        }

        if (inRegionTicks <= RESET_GUARD_SETTLE_TICKS && removalCount > RESET_GUARD_THRESHOLD) {
            return false;
        }

        if (changes.isEmpty()) {
            return false;
        }

        data.putAll(changes);
        return true;
    }

    // --- Persistence ---

    /** Restore persisted houses on login, before the first {@link #update}. */
    public void loadPersisted(Collection<PersistedEntry> entries) {
        clear();
        if (entries == null) {
            return;
        }
        for (PersistedEntry e : entries) {
            BirdhouseSpace space = BirdhouseSpace.fromVarpId(e.getVarpId());
            if (space != null) {
                data.put(space, new Entry(e.getVarpValue(), e.getSeededAtEpochSec()));
            }
        }
    }

    /**
     * Forget all bird house timers. Backs the panel's Reset Bird Houses button, and every load.
     *
     * <p>Also restarts the region-entry count. A login enters the region afresh, and its
     * first varps lag as on any entry. A count carried over from the last session on Fossil
     * Island would skip the reset guard.
     */
    public void clear() {
        data.clear();
        inRegionTicks = 0;
    }

    /** Snapshot for saving to config. */
    public Collection<PersistedEntry> exportForPersist() {
        List<PersistedEntry> out = new ArrayList<>();
        for (Map.Entry<BirdhouseSpace, Entry> e : data.entrySet()) {
            out.add(new PersistedEntry(e.getKey().getVarpId(), e.getValue().varpValue,
                    e.getValue().seededAtEpochSec));
        }
        return out;
    }

    // --- Seed-level probe ---

    /**
     * Refine one house's timer from a "Seed level is: X/10" reading. No-op if the house
     * isn't currently seeded.
     */
    public void applySeedProbe(BirdhouseSpace space, int seedLevel, long nowEpochSec) {
        // One read, so a clear between two reads cannot leave a null entry behind the check.
        Entry e = data.get(space);
        if (e == null || BirdhouseState.fromVarpValue(e.varpValue) != BirdhouseState.SEEDED) {
            return;
        }
        int level = Math.max(0, Math.min(SEED_LEVEL_MAX, seedLevel));
        long elapsed = (SEED_LEVEL_MAX - level) * SECONDS_PER_LEVEL;
        e.seededAtEpochSec = nowEpochSec - elapsed;
    }

    // --- Queries ---

    public BirdhouseState stateOf(BirdhouseSpace space) {
        Entry e = data.get(space);
        return e == null ? BirdhouseState.EMPTY : BirdhouseState.fromVarpValue(e.varpValue);
    }

    /** Build tier of a house, or null if not built. */
    public BirdhouseTier tierOf(BirdhouseSpace space) {
        Entry e = data.get(space);
        return e == null ? null : BirdhouseTier.fromVarpValue(e.varpValue);
    }

    /** Seconds until ready, or 0 if the house isn't seeded / is already ready. */
    public long remainingSeconds(BirdhouseSpace space, long nowEpochSec) {
        long readyAt = readyAtEpochSeconds(space);
        return readyAt < 0 ? 0 : Math.max(0, readyAt - nowEpochSec);
    }

    /**
     * A house's wait as a tooltip line ends: "ready", "1 minute left", "45 minutes left".
     * Minutes round up, so a house never reads ready early.
     */
    public static String timeLeft(long remainingSeconds) {
        if (remainingSeconds <= 0) {
            return "ready";
        }
        long minutes = (remainingSeconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute left" : " minutes left");
    }

    public boolean isReady(BirdhouseSpace space, long nowEpochSec) {
        return isSeeded(space) && remainingSeconds(space, nowEpochSec) == 0;
    }

    /**
     * Epoch second at which the house becomes ready to dismantle, or -1 if it isn't
     * seeded. Independent of "now", so it also serves a house that already completed.
     */
    public long readyAtEpochSeconds(BirdhouseSpace space) {
        // One read of the entry: the panel calls this on the EDT while the client thread writes.
        Entry e = data.get(space);
        return e != null && BirdhouseState.fromVarpValue(e.varpValue) == BirdhouseState.SEEDED
                ? e.seededAtEpochSec + FILL_DURATION_SECONDS : -1;
    }

    public boolean isSeeded(BirdhouseSpace space) {
        return stateOf(space) == BirdhouseState.SEEDED;
    }

    public boolean anySeeded() {
        return Arrays.stream(BirdhouseSpace.values()).anyMatch(this::isSeeded);
    }
}
