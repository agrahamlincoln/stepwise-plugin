package com.stepwise.quest;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;

/**
 * Quest varbit and varplayer IDs mapped to quest names, for detecting quest progress
 * from VarbitChanged events (getVarbitId() for varbit quests, getVarpId() for varplayer
 * quests).
 *
 * <p>Source: Quest Helper's QuestVarbits.java and QuestVarPlayer.java, with numeric IDs
 * resolved from RuneLite's VarbitID.java and VarPlayerID.java. Names match RuneLite's Quest
 * enum, which the resolver looks values up by.
 */
public final class QuestVarbits {

    private QuestVarbits() {
    }

    // --- Varbit quests (newer quests, whose progress is a varbit) ---

    private static final Map<Integer, String> VARBIT_MAP = new HashMap<>();

    // --- VarPlayer quests (older quests, whose progress is a varplayer) ---

    private static final Map<Integer, String> VARPLAYER_MAP = new HashMap<>();

    static {
        // =====================================================================
        // VARBIT QUESTS — F2P
        // =====================================================================
        VARBIT_MAP.put(12063, "Below Ice Mountain");
        VARBIT_MAP.put(2561, "Demon Slayer");
        VARBIT_MAP.put(2378, "Goblin Diplomacy");
        VARBIT_MAP.put(3468, "Misthalin Mystery");
        VARBIT_MAP.put(6071, "The Corsair Curse");
        VARBIT_MAP.put(20106, "The Ides of Milk");
        VARBIT_MAP.put(8063, "X Marks the Spot");

        // =====================================================================
        // VARBIT QUESTS — Members
        // =====================================================================
        VARBIT_MAP.put(3185, "Animal Magnetism");
        VARBIT_MAP.put(13841, "Beneath Cursed Sands");
        VARBIT_MAP.put(299, "Between a Rock...");
        VARBIT_MAP.put(3274, "Contact!");
        VARBIT_MAP.put(487, "Zogre Flesh Eaters");
        VARBIT_MAP.put(2573, "Darkness of Hallowvale");
        VARBIT_MAP.put(2258, "Death to the Dorgeshuun");
        VARBIT_MAP.put(358, "Desert Treasure I");
        VARBIT_MAP.put(14862, "Desert Treasure II - The Fallen Empire");
        VARBIT_MAP.put(1465, "Devious Minds");
        VARBIT_MAP.put(2780, "Eagles' Peak");
        VARBIT_MAP.put(2639, "Elemental Workshop II");
        VARBIT_MAP.put(1560, "Enakhra's Lament");
        VARBIT_MAP.put(2866, "Enlightened Journey");
        VARBIT_MAP.put(2497, "The Eyes of Glouphrie");
        VARBIT_MAP.put(15288, "The Path of Glouphrie");
        VARBIT_MAP.put(1803, "Fairytale I - Growing Pains");
        VARBIT_MAP.put(2326, "Fairytale II - Cure a Queen");
        VARBIT_MAP.put(334, "The Feud");
        VARBIT_MAP.put(822, "Forgettable Tale...");
        VARBIT_MAP.put(961, "Garden of Tranquillity");
        VARBIT_MAP.put(217, "Ghosts Ahoy");
        VARBIT_MAP.put(571, "The Giant Dwarf");
        VARBIT_MAP.put(346, "The Golem");
        VARBIT_MAP.put(1527, "The Hand in the Sand");
        VARBIT_MAP.put(34, "Horror from the Deep");
        VARBIT_MAP.put(418, "Icthlarin's Little Helper");
        VARBIT_MAP.put(1990, "In Aid of the Myreque");
        VARBIT_MAP.put(13599, "Land of the Goblins");
        VARBIT_MAP.put(532, "The Lost Tribe");
        VARBIT_MAP.put(2448, "Lunar Diplomacy");
        VARBIT_MAP.put(1383, "Making History");
        VARBIT_MAP.put(260, "Mountain Daughter");
        VARBIT_MAP.put(1103, "Mourning's End Part II");
        VARBIT_MAP.put(2790, "My Arm's Big Adventure");
        VARBIT_MAP.put(1404, "Ratcatchers");
        VARBIT_MAP.put(1850, "Recipe for Disaster");
        // Each Recipe for Disaster sub-quest advances its own varbit, independently of the
        // container quest's 1850. Without these, questProgress on a sub-quest never resolves.
        VARBIT_MAP.put(1867, "Recipe for Disaster - Wartface & Bentnoze");
        VARBIT_MAP.put(1878, "Recipe for Disaster - Evil Dave");
        VARBIT_MAP.put(1892, "Recipe for Disaster - Mountain Dwarf");
        VARBIT_MAP.put(1895, "Recipe for Disaster - Pirate Pete");
        VARBIT_MAP.put(1896, "Recipe for Disaster - Lumbridge Guide");
        VARBIT_MAP.put(1904, "Recipe for Disaster - Skrach Uglogwee");
        VARBIT_MAP.put(1910, "Recipe for Disaster - Sir Amik Varze");
        VARBIT_MAP.put(1914, "Recipe for Disaster - King Awowogei");
        // "Another Cook's Quest" and "Culinaromancer" have no entry: neither Quest Helper nor
        // RuneLite names a var for either. They complete through questComplete, or questProgress
        // on 1850.
        VARBIT_MAP.put(657, "Recruitment Drive");
        VARBIT_MAP.put(2140, "Royal Trouble");
        VARBIT_MAP.put(2610, "The Slug Menace");
        VARBIT_MAP.put(1372, "Shadow of the Storm");
        VARBIT_MAP.put(2011, "A Soul's Bane");
        VARBIT_MAP.put(1444, "Spirits of the Elid");
        VARBIT_MAP.put(2098, "Swan Song");
        VARBIT_MAP.put(1028, "A Tail of Two Cats");
        VARBIT_MAP.put(451, "Tears of Guthix");
        VARBIT_MAP.put(1051, "Wanted!");
        VARBIT_MAP.put(3293, "Cold War");
        VARBIT_MAP.put(3311, "The Fremennik Isles");
        VARBIT_MAP.put(3337, "Tower of Life");
        VARBIT_MAP.put(3523, "What Lies Below");
        VARBIT_MAP.put(3534, "Olaf's Quest");
        VARBIT_MAP.put(3550, "Another Slice of H.A.M.");
        VARBIT_MAP.put(3618, "Dream Mentor");
        VARBIT_MAP.put(2783, "Grim Tales");
        VARBIT_MAP.put(3888, "King's Ransom");
        VARBIT_MAP.put(5027, "Monkey Madness II");
        VARBIT_MAP.put(5619, "Client of Kourend");
        VARBIT_MAP.put(5795, "Bone Voyage");
        VARBIT_MAP.put(6037, "The Queen of Thieves");
        VARBIT_MAP.put(6027, "The Depths of Despair");
        VARBIT_MAP.put(6104, "Dragon Slayer II");
        VARBIT_MAP.put(6358, "Tale of the Righteous");
        VARBIT_MAP.put(6396, "A Taste of Hope");
        VARBIT_MAP.put(6528, "Making Friends with My Arm");
        VARBIT_MAP.put(7856, "The Ascent of Arceuus");
        VARBIT_MAP.put(7796, "The Forsaken Tower");
        VARBIT_MAP.put(9016, "Song of the Elves");
        VARBIT_MAP.put(9459, "The Fremennik Exiles");
        VARBIT_MAP.put(7255, "Sins of the Father");
        VARBIT_MAP.put(10582, "A Porcine of Interest");
        VARBIT_MAP.put(693, "Getting Ahead");
        VARBIT_MAP.put(12296, "A Kingdom Divided");
        VARBIT_MAP.put(12276, "A Night at the Theatre");
        VARBIT_MAP.put(13738, "Temple of the Eye");
        VARBIT_MAP.put(13902, "Sleeping Giants");
        VARBIT_MAP.put(14609, "The Garden of Death");
        VARBIT_MAP.put(14722, "Secrets of the North");
        VARBIT_MAP.put(9632, "Children of the Sun");
        VARBIT_MAP.put(9655, "Defender of Varrock");
        VARBIT_MAP.put(9835, "At First Light");
        VARBIT_MAP.put(9819, "Perilous Moons");
        VARBIT_MAP.put(9844, "The Ribbiting Tale of a Lily Pad Labour Dispute");
        VARBIT_MAP.put(9649, "Twilight's Promise");
        VARBIT_MAP.put(9653, "While Guthix Sleeps");
        VARBIT_MAP.put(11193, "Ethically Acquired Antiquities");
        VARBIT_MAP.put(11210, "Death on the Isle");
        VARBIT_MAP.put(11182, "Meat and Greet");
        VARBIT_MAP.put(11117, "The Heart of Darkness");
        VARBIT_MAP.put(11479, "The Curse of Arrav");
        VARBIT_MAP.put(16663, "The Final Dawn");
        VARBIT_MAP.put(16632, "Shadows of Custodia");
        VARBIT_MAP.put(16758, "Scrambled!");
        VARBIT_MAP.put(18314, "Pandemonium");
        VARBIT_MAP.put(18317, "Prying Times");
        VARBIT_MAP.put(18282, "Current Affairs");
        VARBIT_MAP.put(18321, "Troubled Tortugans");
        VARBIT_MAP.put(15464, "The Blood Moon Rises");
        VARBIT_MAP.put(18335, "The Red Reef");
        // RuneLite names this varbit DOGQUEST, which does not name the quest.
        VARBIT_MAP.put(15889, "A Ruff Situation");
        VARBIT_MAP.put(15842, "Crab Quest");

        // Miniquests
        VARBIT_MAP.put(5078, "Bear Your Soul");
        VARBIT_MAP.put(3330, "The General's Shadow");
        VARBIT_MAP.put(10570, "Daddy's Home");
        VARBIT_MAP.put(13619, "Hopespear's Will");
        // Quest Helper has no entry for this miniquest; the id is RuneLite's VarbitID.ITT.
        VARBIT_MAP.put(13836, "Into the Tombs");
        VARBIT_MAP.put(14973, "His Faithful Servants");
        // Separate from Mage Arena I, a varplayer below. Id is VarbitID.MA2_PROGRESS, Quest
        // Helper's QUEST_THE_MAGE_ARENA_II.
        VARBIT_MAP.put(6067, "Mage Arena II");

        // =====================================================================
        // VARPLAYER QUESTS — F2P
        // =====================================================================
        VARPLAYER_MAP.put(130, "Black Knights' Fortress");
        VARPLAYER_MAP.put(29, "Cook's Assistant");
        VARPLAYER_MAP.put(31, "Doric's Quest");
        VARPLAYER_MAP.put(176, "Dragon Slayer I");
        VARPLAYER_MAP.put(32, "Ernest the Chicken");
        VARPLAYER_MAP.put(160, "Imp Catcher");
        VARPLAYER_MAP.put(122, "The Knight's Sword");
        VARPLAYER_MAP.put(71, "Pirate's Treasure");
        VARPLAYER_MAP.put(273, "Prince Ali Rescue");
        VARPLAYER_MAP.put(107, "The Restless Ghost");
        VARPLAYER_MAP.put(144, "Romeo & Juliet");
        VARPLAYER_MAP.put(63, "Rune Mysteries");
        VARPLAYER_MAP.put(179, "Sheep Shearer");
        VARPLAYER_MAP.put(145, "Shield of Arrav");
        VARPLAYER_MAP.put(178, "Vampyre Slayer");
        VARPLAYER_MAP.put(67, "Witch's Potion");

        // =====================================================================
        // VARPLAYER QUESTS — Members
        // =====================================================================
        VARPLAYER_MAP.put(293, "Big Chompy Bird Hunting");
        VARPLAYER_MAP.put(68, "Biohazard");
        VARPLAYER_MAP.put(655, "Cabin Fever");
        VARPLAYER_MAP.put(10, "Clock Tower");
        VARPLAYER_MAP.put(399, "Creature of Fenkenstrain");
        VARPLAYER_MAP.put(314, "Death Plateau");
        VARPLAYER_MAP.put(131, "The Dig Site");
        VARPLAYER_MAP.put(80, "Druidic Ritual");
        VARPLAYER_MAP.put(0, "Dwarf Cannon");
        VARPLAYER_MAP.put(335, "Eadgar's Ruse");
        VARPLAYER_MAP.put(299, "Elemental Workshop I");
        VARPLAYER_MAP.put(148, "Family Crest");
        VARPLAYER_MAP.put(17, "Fight Arena");
        VARPLAYER_MAP.put(11, "Fishing Contest");
        VARPLAYER_MAP.put(347, "The Fremennik Trials");
        VARPLAYER_MAP.put(65, "Waterfall Quest");
        VARPLAYER_MAP.put(180, "Gertrude's Cat");
        VARPLAYER_MAP.put(150, "The Grand Tree");
        VARPLAYER_MAP.put(382, "Haunted Mine");
        VARPLAYER_MAP.put(223, "Hazeel Cult");
        VARPLAYER_MAP.put(188, "Heroes' Quest");
        VARPLAYER_MAP.put(5, "Holy Grail");
        VARPLAYER_MAP.put(387, "In Search of the Myreque");
        VARPLAYER_MAP.put(175, "Jungle Potion");
        VARPLAYER_MAP.put(139, "Legends' Quest");
        VARPLAYER_MAP.put(147, "Lost City");
        VARPLAYER_MAP.put(14, "Merlin's Crystal");
        VARPLAYER_MAP.put(365, "Monkey Madness I");
        VARPLAYER_MAP.put(30, "Monk's Friend");
        VARPLAYER_MAP.put(517, "Mourning's End Part I");
        VARPLAYER_MAP.put(192, "Murder Mystery");
        VARPLAYER_MAP.put(307, "Nature Spirit");
        VARPLAYER_MAP.put(112, "Observatory Quest");
        VARPLAYER_MAP.put(416, "One Small Favour");
        VARPLAYER_MAP.put(165, "Plague City");
        VARPLAYER_MAP.put(302, "Priest in Peril");
        VARPLAYER_MAP.put(714, "Rag and Bone Man I");
        VARPLAYER_MAP.put(328, "Regicide");
        VARPLAYER_MAP.put(402, "Roving Elves");
        VARPLAYER_MAP.put(600, "Rum Deal");
        VARPLAYER_MAP.put(76, "Scorpion Catcher");
        VARPLAYER_MAP.put(159, "Sea Slug");
        VARPLAYER_MAP.put(339, "Shades of Mort'ton");
        VARPLAYER_MAP.put(60, "Sheep Herder");
        VARPLAYER_MAP.put(116, "Shilo Village");
        VARPLAYER_MAP.put(320, "Tai Bwo Wannai Trio");
        VARPLAYER_MAP.put(26, "Temple of Ikov");
        VARPLAYER_MAP.put(359, "Throne of Miscellania");
        VARPLAYER_MAP.put(197, "The Tourist Trap");
        VARPLAYER_MAP.put(226, "Witch's House");
        VARPLAYER_MAP.put(111, "Tree Gnome Village");
        VARPLAYER_MAP.put(200, "Tribal Totem");
        VARPLAYER_MAP.put(385, "Troll Romance");
        VARPLAYER_MAP.put(317, "Troll Stronghold");
        VARPLAYER_MAP.put(161, "Underground Pass");
        VARPLAYER_MAP.put(212, "Watchtower");
        VARPLAYER_MAP.put(980, "The Great Brain Robbery");

        // =====================================================================
        // VARPLAYER QUESTS — Miniquests
        // =====================================================================
        VARPLAYER_MAP.put(492, "Enter the Abyss");
        VARPLAYER_MAP.put(77, "Alfred Grimhand's Barcrawl");
        // Quest Helper says "The Mage Arena"; the resolver reads
        // RuneLite's quest name, "Mage Arena I".
        VARPLAYER_MAP.put(267, "Mage Arena I");
    }

    /**
     * Get the quest name for a varbit ID, or null if not a known quest varbit.
     */
    public static String getQuestForVarbit(int varbitId) {
        return VARBIT_MAP.get(varbitId);
    }

    /**
     * Get the quest name for a varplayer ID, or null if not a known quest varplayer.
     */
    public static String getQuestForVarplayer(int varpId) {
        return VARPLAYER_MAP.get(varpId);
    }

    /**
     * Unmodifiable view of all varbit → quest name mappings.
     */
    public static Map<Integer, String> getVarbitMap() {
        return Collections.unmodifiableMap(VARBIT_MAP);
    }

    /**
     * Unmodifiable view of all varplayer → quest name mappings.
     */
    public static Map<Integer, String> getVarplayerMap() {
        return Collections.unmodifiableMap(VARPLAYER_MAP);
    }

    private static final Map<String, Quest> QUESTS_BY_NAME = new HashMap<>();
    private static final Set<Quest> UNMAPPED_QUESTS = EnumSet.noneOf(Quest.class);

    static {
        Set<String> mapped = new HashSet<>(VARBIT_MAP.values());
        mapped.addAll(VARPLAYER_MAP.values());
        for (Quest quest : Quest.values()) {
            QUESTS_BY_NAME.put(quest.getName(), quest);
            if (!mapped.contains(quest.getName())) {
                UNMAPPED_QUESTS.add(quest);
            }
        }
    }

    /** The quest a var here tracks, or null when the var tracks none. */
    public static Quest questOfVar(int varbitId, int varpId) {
        String name = varbitId != -1 ? VARBIT_MAP.get(varbitId) : null;
        if (name == null) {
            name = VARPLAYER_MAP.get(varpId);
        }
        return name != null ? QUESTS_BY_NAME.get(name) : null;
    }

    /**
     * Quests no var here tracks. Their state can move on any var change, so a poll that
     * otherwise follows var changes must always include them.
     */
    public static Set<Quest> unmappedQuests() {
        return Collections.unmodifiableSet(UNMAPPED_QUESTS);
    }
}
