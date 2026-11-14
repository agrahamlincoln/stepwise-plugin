package com.stepwise.quest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stepwise.guide.GuideLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.Reader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Quest stage data from {@code quest_stages.json}, for progress bars. Each quest maps to a
 * sorted array of stage values (varbit/varplayer progression points) and optional stage
 * descriptions.
 *
 * <p>A progress bar's numerator is the stage index; its denominator is the array length
 * or the target stage's index.
 */
@Slf4j
public final class QuestStageData
{
	private static final String RESOURCE = "/com/stepwise/quest_stages.json";

	/** Quest name → sorted stage values. */
	private final Map<String, int[]> stageValues;

	/** Quest name → stage descriptions; absent for quests without descriptions. */
	private final Map<String, String[]> stageDescriptions;

	private QuestStageData(Map<String, int[]> stageValues, Map<String, String[]> stageDescriptions)
	{
		this.stageValues = stageValues;
		this.stageDescriptions = stageDescriptions;
	}

	/**
	 * Loads quest stage data from plugin resources. Returns an empty instance if the
	 * resource is missing or unreadable, so quest criteria get no progress bars.
	 */
	public static QuestStageData loadFromResource(Gson gson)
	{
		return GuideLoader.readResource(RESOURCE, reader -> parse(reader, gson),
			() -> new QuestStageData(Collections.emptyMap(), Collections.emptyMap()));
	}

	public static QuestStageData parse(Reader reader, Gson gson)
	{
		JsonObject root = gson.fromJson(reader, JsonObject.class);
		JsonArray quests = root.getAsJsonArray("quests");
		Map<String, int[]> values = new HashMap<>();
		Map<String, String[]> descriptions = new HashMap<>();

		for (JsonElement el : quests)
		{
			JsonObject quest = el.getAsJsonObject();
			String name = quest.get("questName").getAsString();
			JsonArray sv = quest.getAsJsonArray("stageValues");
			int[] stages = new int[sv.size()];
			for (int i = 0; i < sv.size(); i++)
			{
				stages[i] = sv.get(i).getAsInt();
			}
			values.put(name, stages);

			JsonArray stagesArr = quest.getAsJsonArray("stages");
			if (stagesArr != null && stagesArr.size() > 0)
			{
				String[] descs = new String[stagesArr.size()];
				for (int i = 0; i < stagesArr.size(); i++)
				{
					JsonObject stage = stagesArr.get(i).getAsJsonObject();
					JsonElement desc = stage.get("description");
					descs[i] = desc != null && !desc.isJsonNull() ? desc.getAsString() : null;
				}
				descriptions.put(name, descs);
			}
		}
		log.debug("Loaded quest stage data for {} quests", values.size());
		return new QuestStageData(values, descriptions);
	}

	/**
	 * Returns the index of the largest stage value at or below {@code currentValue}.
	 * Returns 0 if the quest is unknown or the value precedes the first stage.
	 */
	public int getStageIndex(String questName, int currentValue)
	{
		int[] stages = stageValues.get(questName);
		if (stages == null)
		{
			return 0;
		}
		int idx = Arrays.binarySearch(stages, currentValue);
		if (idx >= 0)
		{
			return idx;
		}
		int insertionPoint = -idx - 1;
		return Math.max(0, insertionPoint - 1);
	}

	/**
	 * Returns the total number of stages for a quest, or -1 if unknown.
	 */
	public int getTotalStages(String questName)
	{
		int[] stages = stageValues.get(questName);
		return stages != null ? stages.length : -1;
	}

	/**
	 * Returns the index of an exact stage value, or -1 if not found. Used for
	 * {@code questProgress} conditions.
	 */
	public int getTargetStageIndex(String questName, int targetValue)
	{
		int[] stages = stageValues.get(questName);
		if (stages == null)
		{
			return -1;
		}
		int idx = Arrays.binarySearch(stages, targetValue);
		return idx >= 0 ? idx : -1;
	}

	/**
	 * Returns the stage description for a quest at the given stage index,
	 * or {@code null} if unavailable.
	 */
	public String getStageDescription(String questName, int stageIndex)
	{
		String[] descs = stageDescriptions.get(questName);
		if (descs == null || stageIndex < 0 || stageIndex >= descs.length)
		{
			return null;
		}
		return descs[stageIndex];
	}

	/**
	 * Returns true if stage data is available for the given quest.
	 */
	public boolean hasQuest(String questName)
	{
		return stageValues.containsKey(questName);
	}
}
