package com.stepwise.guide;

import com.google.gson.Gson;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Maps step ids saved against an earlier guide onto the shipped one. Step ids are positional, so
 * a guide that inserts a step renumbers every later step in its entry. Each transition records,
 * for one earlier guide, the ids that changed and the steps the next guide lacks.
 */
@Value
public class StepRemaps {
    private static final String RESOURCE = "/com/stepwise/step_remaps.json";

    /** Ordered oldest first. */
    List<Transition> transitions;

    @Value
    static class Transition {
        /** The step-id version of the guide the ids were saved against. */
        String from;
        /** That guide's version, matched when a save holds only a guide version. */
        String fromGuideVersion;
        /** The step-id version of the guide the ids map onto. */
        String to;
        /** Old id to new id, for every id that changed. */
        Map<String, String> moved;
        /** Old ids whose step the next guide lacks. */
        Set<String> removed;
    }

    /** The bundled table, or null when it is absent or unreadable. */
    public static StepRemaps loadFromResource(Gson base) {
        return GuideLoader.readResource(RESOURCE, reader -> base.fromJson(reader, StepRemaps.class),
                () -> null);
    }

    /**
     * How to rewrite ids saved against step-id version {@code savedStepIds}, or against
     * guide version {@code savedVersion} when the save holds only a guide version, so they
     * name steps of the guide at step-id version {@code target}. The function returns null
     * for a step that guide lacks. Returns null when no transition starts at the saved version.
     *
     * <p>Transitions chain: one ending where another starts leads on to it, until one reaches
     * {@code target} or none starts where the last ended. The list runs oldest first and each
     * transition leads to a newer guide, so one pass finds the chain, and a save with only a
     * guide version starts at the oldest transition from that version.
     */
    public UnaryOperator<StepId> remapper(String savedStepIds, String savedVersion, String target) {
        // A save with neither version belongs to a new account, whatever a transition lacking
        // a guide version would otherwise match.
        if (transitions == null || target == null || savedStepIds == null && savedVersion == null) {
            return null;
        }
        List<Transition> chain = new ArrayList<>();
        String at = savedStepIds;
        for (Transition tr : transitions) {
            if (target.equals(at)) {
                break;
            }
            if (at != null ? at.equals(tr.from) : Objects.equals(savedVersion, tr.fromGuideVersion)) {
                chain.add(tr);
                at = tr.to;
            }
        }
        if (chain.isEmpty()) {
            return null;
        }
        return id -> {
            String value = id.getValue();
            for (Transition tr : chain) {
                // Gson leaves a key the table omits null.
                if (tr.removed != null && tr.removed.contains(value)) {
                    return null;
                }
                if (tr.moved != null) {
                    value = tr.moved.getOrDefault(value, value);
                }
            }
            return new StepId(value);
        };
    }
}
