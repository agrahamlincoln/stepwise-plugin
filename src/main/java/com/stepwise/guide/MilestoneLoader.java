package com.stepwise.guide;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;

import java.io.Reader;

/**
 * Loads {@code milestones.json} from plugin resources.
 *
 * <p>Returns {@code null}, with a warning, if the resource is absent, cannot be read, or
 * holds no milestone list. The plugin then runs without milestone auto-latching.
 */
@Slf4j
public final class MilestoneLoader {

    private static final String MILESTONES_RESOURCE = "/com/stepwise/milestones.json";

    private MilestoneLoader() {
    }

    public static MilestonesFile loadFromResource(Gson base) {
        return GuideLoader.readResource(MILESTONES_RESOURCE, reader -> parse(reader, base), () -> null);
    }

    /**
     * Parses a milestones file, or returns null when it is malformed or has no milestone list.
     * The plugin loads this during startup, so a throw here would stop the whole plugin over
     * an optional file.
     */
    static MilestonesFile parse(Reader reader, Gson base) {
        MilestonesFile file;
        try {
            file = GuideLoader.createGson(base).fromJson(reader, MilestonesFile.class);
        } catch (JsonParseException e) {
            log.warn("Failed to parse milestones.json — milestone auto-latching disabled", e);
            return null;
        }
        if (file == null || file.getMilestones() == null) {
            log.warn("milestones.json has no milestone list — milestone auto-latching disabled");
            return null;
        }
        log.debug("Loaded {} milestones", file.getMilestones().size());
        return file;
    }
}
