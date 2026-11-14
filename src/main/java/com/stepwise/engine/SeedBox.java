package com.stepwise.engine;

import java.util.Set;

import com.stepwise.model.PlayerState;
import net.runelite.api.gameval.ItemID;

/**
 * The Tithe Farm seed box: what counts as one, and where it is.
 *
 * <p>The box plants straight from itself when used on a patch, so seeds in a box in the bag
 * are in the bag. {@code ResolutionEngine} counts its contents toward wherever the box is, as
 * it does for the rune pouch. The tackle box is carried too, but its tools must come out
 * before they can be used, and its bait, usable from the box only for fishing, must come out
 * for anything else (feathers for fletching, karambwanji for a quest). Its contents, like the
 * seed vault's, therefore count only as owned.
 *
 * <p>The rune pouch is read from varbits and corrects itself whenever they change. The game
 * never reports the seed box's contents as a container, so its tally is summed from chat
 * lines. Seeds boxed before the plugin watched, and movements it missed, are not counted, and
 * only a Check listing of the box corrects the tally.
 */
public final class SeedBox {

    private SeedBox() {
    }

    /**
     * Closed and open: one item in two states. Open and Close swap the id in place, and a
     * player may own only one box.
     *
     * <p>Named through {@code ItemID} because nearby ids are traps: {@code ItemID.SEEDBOX} is
     * a <em>seed pack</em>, and the box has a bank placeholder id too.
     */
    private static final Set<Integer> BOX_ITEM_IDS =
            Set.of(ItemID.SEED_BOX, ItemID.SEED_BOX_OPEN);

    /** True when a seed box is in the inventory or worn. */
    public static boolean carried(PlayerState state) {
        return HeldItems.carried(state, BOX_ITEM_IDS);
    }

    /**
     * True when the box is in the bag <em>and</em> we trust the tally of what is in it.
     *
     * <p>The chat-summed tally cannot correct an over-count, so a tally inherited from a
     * previous session is not trusted. Either of two things earns trust: a Check listing of
     * the whole box this session ({@code seedBoxVerifiedAt}), or seeds we watched go in or out
     * this session ({@code seedBoxWatchedAt}). Neither stamp survives a restart.
     *
     * <p>A watched movement on top of stale surplus is believed in full. Only a Check listing
     * tells those apart.
     *
     * <p>The one predicate for boxed seeds counting as carried. {@code ResolutionEngine} reads
     * it in the side-container count that both the progress bar and the tick beneath it use,
     * so the two agree.
     */
    public static boolean contentsCarried(PlayerState state) {
        return carried(state)
                && (state.getSeedBoxVerifiedAt() != null
                        || state.getSeedBoxWatchedAt() != null);
    }

    /** True when a seed box is sitting in the bank. */
    public static boolean banked(PlayerState state) {
        return HeldItems.banked(state, BOX_ITEM_IDS);
    }
}
