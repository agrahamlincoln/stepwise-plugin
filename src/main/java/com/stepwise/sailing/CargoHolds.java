package com.stepwise.sailing;

import net.runelite.api.gameval.InventoryID;

/**
 * The item containers behind the cargo holds of the player's boats.
 *
 * <p>Each of the five boat slots has its own hold. The game reports a hold only while the
 * player looks in it, like the bank and the seed vault, so the last contents seen are a
 * snapshot to persist.
 *
 * <p>A scrapped boat takes its hold's contents with it, so a snapshot counts only while
 * {@link BoatVarbits#owned} says the slot is the player's.
 */
public final class CargoHolds {
    private static final int[] CONTAINERS = {
        InventoryID.SAILING_BOAT_1_CARGOHOLD, InventoryID.SAILING_BOAT_2_CARGOHOLD,
        InventoryID.SAILING_BOAT_3_CARGOHOLD, InventoryID.SAILING_BOAT_4_CARGOHOLD,
        InventoryID.SAILING_BOAT_5_CARGOHOLD,
    };

    private CargoHolds() {
    }

    /** The container id of a 0-based boat slot's hold. */
    public static int containerOf(int slot) {
        return CONTAINERS[slot];
    }

    /** The 0-based boat slot whose hold a container id is, or -1 when it is not a hold. */
    public static int slotOf(int containerId) {
        for (int slot = 0; slot < CONTAINERS.length; slot++) {
            if (CONTAINERS[slot] == containerId) {
                return slot;
            }
        }
        return -1;
    }
}
