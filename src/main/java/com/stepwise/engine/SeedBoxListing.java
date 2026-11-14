package com.stepwise.engine;

import com.stepwise.model.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A Check listing part way through arriving.
 *
 * <p>Checking the seed box prints a header line, then one chat message per seed, and no
 * closing line. {@link EventParser} stays stateless; the caller owns one of these while a
 * listing is open and drops it when a message arrives that is not an entry.
 *
 * <p><b>The entry wording is not self-identifying.</b> The game prints "2 x Guam seed." in
 * places unrelated to farming, so an entry counts only after the header has been seen.
 */
public final class SeedBoxListing {

    /**
     * The seeds and spores the guide's farm runs plant, by item id. The plugin learns their names
     * up front, because a Check made before the bank or inventory has shown a seed would
     * otherwise name nothing.
     */
    public static final List<Integer> SEED_ITEM_IDS = List.of(
            // Herbs, guam to torstol.
            5291, 5292, 5293, 5294, 5295, 5296, 5297, 5298, 5299, 5300, 5301, 5302, 5303, 5304,
            // Trees: acorn to magic, then teak and mahogany.
            5312, 5313, 5314, 5315, 5316, 21486, 21488,
            // Fruit trees: apple to palm, then dragonfruit.
            5283, 5284, 5285, 5286, 5287, 5288, 5289, 22877,
            // Calquat, celastrus, redwood, Hespori, seaweed spore, mushroom spore.
            5290, 22869, 22871, 22875, 21490, 5282,
            // Cactus, potato cactus.
            5280, 22873,
            // Jangerberry, whiteberry, poison ivy.
            5104, 5105, 5106,
            // Limpwurt, white lily, strawberry, watermelon, snape grass.
            5100, 22887, 5323, 5321, 22879);

    private final List<ItemStack> seeds = new ArrayList<>();
    private int lines;

    /**
     * How many entry lines this listing has printed, named or not.
     *
     * <p>Can exceed the size of {@link #getSeeds} when a name is unknown. A listing whose
     * names we could not place is not an empty box and must not be committed as one.
     */
    public int getLines() {
        return lines;
    }

    /** The seeds read so far, in the order the box listed them. */
    public List<ItemStack> getSeeds() {
        return seeds;
    }

    /**
     * Folds one message into this listing.
     *
     * @param known lower-cased name → item id, as the plugin learns names. A name absent from
     *              it is counted in {@link #getLines} and left out of {@link #getSeeds}:
     *              a missing seed costs a needless withdrawal, an invented one a planting the
     *              player cannot make
     * @return false when the message is not a listing entry, which means the listing has ended
     */
    public static boolean read(String message, SeedBoxListing into, Map<String, Integer> known) {
        Optional<EventParser.SeedBoxMove> entry =
                EventParser.parseSeedBoxContentsEntry(message);
        if (!entry.isPresent()) {
            return false;
        }
        into.lines++;
        String name = entry.get().getItemName();
        Integer itemId = known.get(name.toLowerCase(Locale.ROOT));
        if (itemId != null) {
            into.seeds.add(new ItemStack(itemId, entry.get().getQuantity(), name));
        }
        return true;
    }
}
