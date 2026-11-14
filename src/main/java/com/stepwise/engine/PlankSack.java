package com.stepwise.engine;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.stepwise.model.PlayerState;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * The Mahogany Homes plank sack: what it holds, and where it is.
 *
 * <p>A carried container like the seed box, but read like the rune pouch: the game keeps one
 * count varbit per plank type and transmits it without the sack being opened, so the contents
 * are always current. Planks in the sack appear in neither the inventory nor the bank.
 */
public final class PlankSack {

    private PlankSack() {
    }

    /** The sack itself: a single id, with no open/closed pair and no charged variants. */
    private static final Set<Integer> SACK_ITEM_IDS = Set.of(ItemID.PLANK_SACK);

    /** Per-plank-type count varbits, index-aligned with {@link #PLANK_ITEM_IDS}. */
    private static final int[] COUNT_VARBITS = {
            VarbitID.PLANK_SACK_PLAIN,
            VarbitID.PLANK_SACK_OAK,
            VarbitID.PLANK_SACK_TEAK,
            VarbitID.PLANK_SACK_MAHOGANY,
            VarbitID.PLANK_SACK_CAMPHOR,
            VarbitID.PLANK_SACK_IRONWOOD,
            VarbitID.PLANK_SACK_ROSEWOOD,
    };

    /** Plank item IDs, index-aligned with {@link #COUNT_VARBITS}. */
    private static final int[] PLANK_ITEM_IDS = {
            ItemID.WOODPLANK,
            ItemID.PLANK_OAK,
            ItemID.PLANK_TEAK,
            ItemID.PLANK_MAHOGANY,
            ItemID.PLANK_CAMPHOR,
            ItemID.PLANK_IRONWOOD,
            ItemID.PLANK_ROSEWOOD,
    };

    /**
     * The count varbits, exposed so the plugin can scan them at login. No completion
     * condition names them, so they are absent from {@code requiredVarIds} and would
     * otherwise stay unread until the player next moved a plank in or out.
     */
    public static final Set<Integer> CONTENT_VARBIT_IDS =
            Arrays.stream(COUNT_VARBITS).boxed().collect(Collectors.toUnmodifiableSet());

    /**
     * How many planks of {@code itemId} the sack holds, or 0 for anything it cannot hold.
     *
     * <p>Ignores where the sack is; {@code ResolutionEngine} checks that per location.
     */
    public static int stored(PlayerState state, int itemId) {
        if (state == null || itemId == 0) {
            return 0;
        }
        for (int type = 0; type < PLANK_ITEM_IDS.length; type++) {
            if (PLANK_ITEM_IDS[type] == itemId) {
                Integer count = state.getVarbits().get(COUNT_VARBITS[type]);
                return (count != null && count > 0) ? count : 0;
            }
        }
        return 0;
    }

    /** True when a plank sack is in the inventory or worn. */
    public static boolean carried(PlayerState state) {
        return HeldItems.carried(state, SACK_ITEM_IDS);
    }

    /** True when a plank sack is sitting in the bank. */
    public static boolean banked(PlayerState state) {
        return HeldItems.banked(state, SACK_ITEM_IDS);
    }
}
