package com.stepwise.guide;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loads the Stepwise guide from JSON.
 *
 * <p>The loader flattens chapters &gt; sections &gt; entries &gt; steps into a cached step list
 * and builds a step-to-context map.
 */
@Slf4j
public final class GuideLoader {
    private static final String GUIDE_RESOURCE = "/com/stepwise/guide.json";

    private GuideLoader() {
    }

    /**
     * Extend the client's Gson with the guide's type adapters. {@code base} is RuneLite's
     * injected instance, which is reused so the client's Gson settings apply to the guide.
     */
    public static Gson createGson(Gson base) {
        return base.newBuilder()
                .registerTypeAdapter(CompletionCondition.class, new CompletionCondition.TypeAdapter())
                .registerTypeAdapter(StepActivation.class, new StepActivation.TypeAdapter())
                .registerTypeAdapter(StepId.class, new StepId.TypeAdapter())
                .create();
    }

    /**
     * Parse a bundled resource as UTF-8. A missing, unreadable or malformed resource logs a
     * warning and yields {@code fallback}, so an optional file cannot stop the plugin.
     */
    public static <T> T readResource(String path, Function<Reader, T> parse, Supplier<T> fallback) {
        InputStream is = GuideLoader.class.getResourceAsStream(path);
        if (is == null) {
            log.warn("{} not found in plugin resources", path);
            return fallback.get();
        }
        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return parse.apply(reader);
        } catch (IOException | RuntimeException e) {
            // A parser that walks the JSON by hand fails on a missing key with a
            // NullPointerException or ClassCastException, so any RuntimeException takes the
            // fallback.
            log.warn("Failed to load {}", path, e);
            return fallback.get();
        }
    }

    public static StepwiseGuide loadFromResource(Gson base) {
        return readResource(GUIDE_RESOURCE, reader -> parse(reader, base), () -> {
            throw new IllegalStateException("Failed to load the guide from " + GUIDE_RESOURCE);
        });
    }

    public static StepwiseGuide parse(Reader reader, Gson base) {
        return freezeGuide(createGson(base).fromJson(reader, StepwiseGuide.class));
    }

    /**
     * Build the cached step list, context map, position index, assumption spans and
     * hardwood carriers, and wrap the chapter list and each of these in an unmodifiable view.
     * The chapters' own sections and steps are not wrapped.
     */
    public static StepwiseGuide freezeGuide(StepwiseGuide raw) {
        List<Chapter> chapters = raw.getChapters() != null
                ? Collections.unmodifiableList(raw.getChapters())
                : Collections.emptyList();

        List<GuideStep> allSteps = Collections.unmodifiableList(
                StepwiseGuide.flattenSteps(chapters));
        Map<StepId, StepContext> contextMap = Collections.unmodifiableMap(
                StepwiseGuide.buildStepContextMap(chapters));
        Map<String, Integer> positionIndex = Collections.unmodifiableMap(
                StepwiseGuide.buildStepPositionIndex(allSteps));
        List<AssumptionSpan> assumptionSpans = Collections.unmodifiableList(
                StepwiseGuide.buildAssumptionSpans(allSteps, positionIndex));
        List<HardwoodCarrier> hardwoodCarriers = Collections.unmodifiableList(
                StepwiseGuide.buildHardwoodCarriers(allSteps));

        return new StepwiseGuide(
                raw.getTitle(),
                raw.getVersion(),
                raw.getTotalMinutes(),
                chapters,
                allSteps,
                contextMap,
                positionIndex,
                assumptionSpans,
                hardwoodCarriers);
    }
}
