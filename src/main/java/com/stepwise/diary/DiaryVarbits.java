package com.stepwise.diary;

import com.stepwise.model.PlayerState;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The game's own completion flag for each achievement diary tier.
 *
 * <p>Every area keeps one varbit per tier, set when the tier is finished and readable from
 * login onwards. Unlike the one-shot chat message announcing the diary, the varbit also
 * reveals tiers finished before the plugin was installed.
 *
 * <p>The guide resolver's {@code diaryComplete} steps read this table.
 *
 * <p>Ids are transcribed from RuneLite's {@code Varbits} class
 * (DIARY_&lt;AREA&gt;_&lt;TIER&gt;). Every lookup of a diary's completion or reward-claimed flag
 * goes through this table.
 */
public final class DiaryVarbits {

    private DiaryVarbits() {
    }

    public static final String EASY = "Easy";
    public static final String MEDIUM = "Medium";
    public static final String HARD = "Hard";
    public static final String ELITE = "Elite";

    /**
     * Long-form area names folded onto the short key the table is written with.
     *
     * <p>The table uses the one-word name; the guide and the wiki use the full title. Without
     * the alias, a {@code diaryComplete} condition naming "Lumbridge &amp; Draynor" would
     * report unknown forever. Both "&amp;" and "and" are accepted.
     *
     * <p>Declared above {@link #BY_AREA_AND_TIER} because building that table runs every
     * area name through {@link #key}, which reads this one.
     */
    private static final Map<String, String> AREA_ALIASES = areaAliases();

    private static Map<String, String> areaAliases() {
        Map<String, String> aliases = new HashMap<>();
        alias(aliases, "Lumbridge & Draynor", "Lumbridge");
        alias(aliases, "Kourend & Kebos", "Kourend");
        alias(aliases, "Western Provinces", "Western");
        return Collections.unmodifiableMap(aliases);
    }

    private static void alias(Map<String, String> aliases, String longName, String shortName) {
        String canonical = shortName.toLowerCase(Locale.ROOT);
        String folded = longName.toLowerCase(Locale.ROOT);
        aliases.put(folded, canonical);
        aliases.put(folded.replace(" & ", " and "), canonical);
    }

    /**
     * Completion varbit per area and tier, keyed case-insensitively because the callers
     * are guide conditions, which need not match the case of the keys.
     */
    private static final Map<String, Integer> BY_AREA_AND_TIER = table();

    private static Map<String, Integer> table() {
        Map<String, Integer> ids = new HashMap<>();
        put(ids, "Ardougne", 4458, 4459, 4460, 4461);
        put(ids, "Desert", 4483, 4484, 4485, 4486);
        put(ids, "Falador", 4462, 4463, 4464, 4465);
        put(ids, "Fremennik", 4491, 4492, 4493, 4494);
        put(ids, "Kandarin", 4475, 4476, 4477, 4478);
        // Karamja's four ids are not consecutive.
        put(ids, "Karamja", 3578, 3599, 3611, 4566);
        put(ids, "Kourend", 7925, 7926, 7927, 7928);
        put(ids, "Lumbridge", 4495, 4496, 4497, 4498);
        put(ids, "Morytania", 4487, 4488, 4489, 4490);
        put(ids, "Varrock", 4479, 4480, 4481, 4482);
        put(ids, "Western", 4471, 4472, 4473, 4474);
        put(ids, "Wilderness", 4466, 4467, 4468, 4469);
        return Collections.unmodifiableMap(ids);
    }

    private static void put(Map<String, Integer> ids, String area,
            int easy, int medium, int hard, int elite) {
        ids.put(key(area, EASY), easy);
        ids.put(key(area, MEDIUM), medium);
        ids.put(key(area, HARD), hard);
        ids.put(key(area, ELITE), elite);
    }

    /** The table key for an area and tier; null, which no entry has, when either is missing. */
    private static String key(String area, String tier) {
        if (area == null || tier == null) {
            return null;
        }
        String folded = area.toLowerCase(Locale.ROOT).trim();
        return AREA_ALIASES.getOrDefault(folded, folded) + "/" + tier.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * The completion varbit for one diary tier, or null when the area or tier is not one
     * the game keeps a flag for.
     *
     * <p>Accepts both the short name ("Kourend") and the full title via {@link #AREA_ALIASES}.
     */
    public static Integer varbitFor(String area, String tier) {
        return BY_AREA_AND_TIER.get(key(area, tier));
    }

    /**
     * Whether this diary tier is finished: TRUE when the flag is set, FALSE when it reads
     * zero, and null when the varbit has not been reported or the area or tier has no id.
     *
     * <p>Null differs from FALSE: an absent varbit means nobody has looked yet, not "not
     * done".
     */
    public static Boolean completed(String area, String tier, PlayerState state) {
        return flag(varbitFor(area, tier), state);
    }

    private static Boolean flag(Integer varbit, PlayerState state) {
        Integer value = state != null && varbit != null ? state.getVarbits().get(varbit) : null;
        return value != null ? value > 0 : null;
    }

    /**
     * The flag set when the taskmaster hands the tier's reward over, a separate event from
     * finishing the tier's tasks.
     *
     * <p>A tier can sit finished but unclaimed indefinitely. A "collect the reward" step reads
     * this flag; a "complete the tier" step reads {@link #BY_AREA_AND_TIER}.
     *
     * <p>Preferred over the reward item because the flag is permanent: the antique lamp is
     * consumed on use and the equippable can be dropped or destroyed.
     *
     * <p>Karamja's ids are laid out unlike the other areas'. Its easy, medium and hard claim flags
     * ({@code ATJUN_EASY_REWARD}, {@code ATJUN_MED_REWARD}, {@code ATJUN_HARD_REWARD}) sit one
     * below their tier-complete flags ({@code ATJUN_*_DONE}); the elite one is
     * {@code KARAMJA_ELITE_REWARD}. The game's {@code diary_completion_info} script reads these
     * four as Karamja's reward state. Ids from RuneLite's gameval {@code VarbitID}.
     */
    private static final Map<String, Integer> REWARD_BY_AREA_AND_TIER = rewardTable();

    private static Map<String, Integer> rewardTable() {
        Map<String, Integer> ids = new HashMap<>();
        put(ids, "Ardougne", 4499, 4500, 4501, 4502);
        put(ids, "Desert", 4523, 4524, 4525, 4526);
        put(ids, "Falador", 4503, 4504, 4505, 4506);
        put(ids, "Fremennik", 4531, 4532, 4533, 4534);
        put(ids, "Kandarin", 4515, 4516, 4517, 4518);
        put(ids, "Karamja", 3577, 3598, 3610, 4567);
        put(ids, "Kourend", 7929, 7930, 7931, 7932);
        put(ids, "Lumbridge", 4535, 4536, 4537, 4538);
        put(ids, "Morytania", 4527, 4528, 4529, 4530);
        put(ids, "Varrock", 4519, 4520, 4521, 4522);
        put(ids, "Western", 4511, 4512, 4513, 4514);
        put(ids, "Wilderness", 4507, 4508, 4509, 4510);
        return Collections.unmodifiableMap(ids);
    }

    /**
     * The reward-claimed varbit for one diary tier, or null when the area or tier is not one
     * the game keeps a flag for.
     */
    public static Integer rewardVarbitFor(String area, String tier) {
        return REWARD_BY_AREA_AND_TIER.get(key(area, tier));
    }

    /**
     * Whether this tier's reward has been claimed from the taskmaster, three-valued on the
     * same terms as {@link #completed}: null means nothing has reported it.
     */
    public static Boolean rewardClaimed(String area, String tier, PlayerState state) {
        return flag(rewardVarbitFor(area, tier), state);
    }
}
