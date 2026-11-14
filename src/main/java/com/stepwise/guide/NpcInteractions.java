package com.stepwise.guide;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The NPC names the loaded guide asks about, and the record format that both sides of an
 * {@code npcInteraction} match agree on.
 *
 * <p>The game keeps no record of an interaction, so the plugin records each one and never
 * forgets it, in the player's profile config. Recording every NPC click would add a record for
 * every monster, shopkeeper and passer-by the account ever clicks, so the record would keep
 * growing. The watchlist limits it to the few NPCs the guide names. See also
 * {@link WatchedObjectIds}.
 *
 * <p>This class owns the key format so the writer ({@code CoreEngine.onNpcInteraction}) and
 * the reader ({@code ResolutionEngine}) cannot normalize differently. A key mismatch looks
 * exactly like an interaction that never happened.
 *
 * <p>Menu clicks are human-paced, so unlike {@link WatchedObjectIds} this class allocates freely.
 */
public final class NpcInteractions {
    /**
     * The menu options worth recording, and so the only actions a condition can name and
     * still be satisfiable.
     */
    private static final Set<String> TRACKED_ACTIONS = Set.of(
            "talk-to", "trade", "attack", "pickpocket", "bank", "collect", "pick-up");

    /**
     * Separator between the NPC name and the action in a record key. Printable because the
     * keys live in the text-format profile config. No OSRS NPC name or menu action contains
     * a slash.
     */
    private static final String SEPARATOR = "/";

    /** Curly quotes folded by {@link #normalize}, as code points so source encoding cannot matter. */
    private static final char LEFT_SINGLE_QUOTE = 0x2018;
    private static final char RIGHT_SINGLE_QUOTE = 0x2019;
    private static final char LEFT_DOUBLE_QUOTE = 0x201C;
    private static final char RIGHT_DOUBLE_QUOTE = 0x201D;

    private final Set<String> names;

    private NpcInteractions(Set<String> names) {
        this.names = names;
    }

    /** Whether a click on this menu option is worth recording. Action is normalized here. */
    public static boolean isTrackedAction(String action) {
        return TRACKED_ACTIONS.contains(normalize(action));
    }

    /** Whether the guide asks about interactions with this NPC. Name is normalized here. */
    public boolean watches(String npcName) {
        return names.contains(normalize(npcName));
    }

    /** How many distinct NPCs the guide asks about. */
    public int size() {
        return names.size();
    }

    /** Collect the NPC names named by every condition in the guide. */
    public static NpcInteractions fromGuide(StepwiseGuide guide) {
        Set<String> names = new HashSet<>();
        for (CompletionCondition condition : guide.leafConditions()) {
            if (condition instanceof CompletionCondition.NpcInteraction) {
                String name = normalize(((CompletionCondition.NpcInteraction) condition).getNpcName());
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return new NpcInteractions(names);
    }

    /**
     * The key under which one observed interaction is recorded.
     *
     * @param npcName the NPC as the game names it
     * @param action  the menu option clicked; never null at an observation site
     */
    public static String record(String npcName, String action) {
        return normalize(npcName) + SEPARATOR + normalize(action);
    }

    /**
     * Whether the observed interactions satisfy an {@code npcInteraction} condition.
     *
     * <p>A condition with no action is met by any tracked interaction with the NPC. A
     * condition with an action needs that action: talking to a banker is not banking.
     *
     * @param observed grow-only record of interaction keys from {@link #record}
     */
    public static boolean satisfiedBy(Set<String> observed, String npcName, String action) {
        if (observed == null || observed.isEmpty()) {
            return false;
        }
        String name = normalize(npcName);
        if (name.isEmpty()) {
            return false;
        }
        String wantedAction = normalize(action);
        if (!wantedAction.isEmpty()) {
            return observed.contains(name + SEPARATOR + wantedAction);
        }
        String prefix = name + SEPARATOR;
        for (String key : observed) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folds curly quotes to ASCII, trims and lowercases.
     * Guide text uses curly apostrophes and game names are ASCII, so a raw comparison would
     * miss every apostrophe-bearing name.
     */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(LEFT_SINGLE_QUOTE, '\'').replace(RIGHT_SINGLE_QUOTE, '\'')
                .replace(LEFT_DOUBLE_QUOTE, '"').replace(RIGHT_DOUBLE_QUOTE, '"')
                .trim().toLowerCase(Locale.ROOT);
    }
}
