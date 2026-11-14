package com.stepwise.engine;

import java.util.Set;

import com.stepwise.model.PlayerState;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * The Vale Totems bow string spool: how many strings it holds, and where it is.
 *
 * <p>Read like the plank sack: the game keeps the string count in one varbit and transmits it
 * without the spool being opened, so the count is always current. Strings wound onto the
 * spool leave the inventory and the bank, so without this a full spool reads as no bow
 * string at all.
 *
 * <p>The count is bound to the account, not the item: per the wiki, a replacement spool from
 * the Vale Research Exchange picks the charges back up. Callers still require a spool to be
 * present, because a player with no spool cannot use the strings yet.
 */
public final class BowStringSpool {

    private BowStringSpool() {
    }

    /** The spool itself: one item id, with no open/closed pair or charged variants. */
    private static final Set<Integer> SPOOL_ITEM_IDS = Set.of(ItemID.BOWSTRING_SPOOL);

    /**
     * The count varbit, exposed so the plugin can scan it at login. No completion condition
     * names it, so it is absent from {@code requiredVarIds} and would otherwise stay unread
     * after a relog until the player next winds or unwinds a string.
     */
    public static final Set<Integer> CONTENT_VARBIT_IDS = Set.of(VarbitID.BOWSTRING_SPOOL_CHARGES);

    /**
     * How many bow strings the spool holds, or 0 for anything else.
     *
     * <p>Ignores where the spool is. {@code ResolutionEngine} checks that per location.
     */
    public static int stored(PlayerState state, int itemId) {
        if (state == null || itemId != ItemID.BOW_STRING) {
            return 0;
        }
        Integer count = state.getVarbits().get(VarbitID.BOWSTRING_SPOOL_CHARGES);
        return (count != null && count > 0) ? count : 0;
    }

    /** True when a spool is in the inventory or worn. */
    public static boolean carried(PlayerState state) {
        return HeldItems.carried(state, SPOOL_ITEM_IDS);
    }

    /** True when a spool is sitting in the bank. */
    public static boolean banked(PlayerState state) {
        return HeldItems.banked(state, SPOOL_ITEM_IDS);
    }
}
