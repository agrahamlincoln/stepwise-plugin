package com.stepwise.engine;

import com.stepwise.model.ItemStack;
import com.stepwise.model.PlayerState;

import java.util.Collection;
import java.util.Set;

/** Where a carried container such as the seed box or plank sack sits among the player's items. */
final class HeldItems {

    private HeldItems() {
    }

    /** True when one of these items is in the inventory or worn. */
    static boolean carried(PlayerState state, Set<Integer> itemIds) {
        return state != null
                && (holds(state.getInventory(), itemIds)
                        || state.getEquipment() != null
                                && holds(state.getEquipment().values(), itemIds));
    }

    /** True when one of these items is sitting in the bank. */
    static boolean banked(PlayerState state, Set<Integer> itemIds) {
        return state != null && holds(state.getBank(), itemIds);
    }

    private static boolean holds(Collection<ItemStack> items, Set<Integer> itemIds) {
        if (items == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (item != null && itemIds.contains(item.getItemId())) {
                return true;
            }
        }
        return false;
    }
}
