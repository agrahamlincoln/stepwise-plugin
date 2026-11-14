package com.stepwise.guide;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

@Value
@Builder
public class GuideStep {
    @SerializedName("id") StepId id;
    @SerializedName("instruction") String instruction;
    @SerializedName("guidance") String guidance;
    @SerializedName("completion") CompletionCondition completion;
    @SerializedName("prerequisites") List<StepId> prerequisites;
    /**
     * Optional navigation/preparation steps shown before this step. Pre-steps complete only
     * from direct game-state evaluation. They take no part in DAG serves edges or DAG-driven
     * auto-latch, and they never gate this step's completion.
     */
    @Builder.Default
    @SerializedName("preSteps") List<PreStep> preSteps = Collections.emptyList();
    /**
     * When true, this step auto-latches on catch-up once any downstream step that lists it as
     * a prerequisite is genuinely completed, even though its own condition no longer holds.
     * Set for steps whose completion reads a categorical varbit that holds only the current
     * state (e.g. POH house-location varbit 2187). Once the guide moves the house again, the
     * exact-match check stays false and the step would stick on a fresh catch-up.
     * <p>
     * Primitive boolean on purpose: Gson bypasses the builder, so {@code @Builder.Default}
     * does not apply, and an absent field must read as false rather than null.
     */
    @Builder.Default
    @SerializedName("softLatch") boolean softLatch = false;
    /**
     * When true, this step is an open-ended activity that runs alongside the rest of the guide:
     * a long skill grind, a recurring activity, or a crop-growth wait. It latches like any other
     * step but resolves to {@link StepStatus#BACKGROUND} rather than {@link StepStatus#ACTIVE},
     * so the active-step pointer moves past it. Primitive for the reason given on {@link #softLatch}.
     */
    @Builder.Default
    @SerializedName("background") boolean background = false;
    /**
     * Gate that must hold before this {@link #background} step is <em>shown</em> in the panel's
     * In Progress list. It has no effect on resolution, prerequisites or auto-latching. The row
     * appearing tells the player the activity has become doable, so a step that cannot start
     * for hours does not sit in the list looking as actionable as one that can.
     * <p>
     * Evaluated against base levels, never boosted ones; see
     * {@link com.stepwise.engine.ActivationGate#isOpen}. Null means always shown.
     */
    @SerializedName("activation") StepActivation activation;
    /**
     * The occasion this step rides along with — "Next farm run", "Next Varrock Museum visit",
     * "Ongoing".
     * Display only, and only meaningful alongside {@link #background}.
     * <p>
     * A background step has no position in the sequence to tell the player when to act on it.
     * {@link #activation} covers a level requirement. This field covers a trip, which no game
     * state expresses. The In Progress list groups steps by this string.
     * <p>
     * Null means no occasion is known, and the row renders without a label.
     */
    @SerializedName("when") String when;
    /**
     * A standing habit that starts at this step, shown as a "From now on" callout on the step
     * card while this is the current step. Never set on a {@link #background} step, which is
     * never the current card. Null means no callout.
     */
    @SerializedName("fromNowOn") String fromNowOn;
    /**
     * What the route expects the player to keep on them from this step on. The side panel
     * shows it while the check reads unmet and the bank has been seen, or for an
     * {@code inventory} check only while the bank is open. The game overlay shows it only while
     * the bank is open. Null or empty means none.
     */
    @SerializedName("assumes") List<StepAssumption> assumes;
    /**
     * The Miscellania hardwoods the route accepts once this step is complete, first choice
     * first, as {@link com.stepwise.engine.KingdomTracker.Hardwood} labels. The last completed
     * step carrying it, in guide order, sets the advice. Null or empty means this step sets
     * none.
     */
    @SerializedName("kingdomHardwood") List<String> kingdomHardwood;
}
