package com.stepwise.engine;

import com.stepwise.guide.StepwiseGuide;
import com.stepwise.guide.Milestone;
import com.stepwise.guide.MilestonesFile;
import com.stepwise.guide.StepId;
import com.stepwise.model.PlayerState;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Evaluates milestones against current player state to find the guide's auto-latch position.
 *
 * <p>The file lists milestones in guide order, so the last cleared one is the furthest. It wins,
 * and every guide step up to and including its anchor is marked completed. An unmet earlier
 * milestone does not block later ones. Each milestone is independent evidence of position, and a
 * player can skip an optional target (e.g. leave Wintertodt before Firemaking 99) while being
 * sections ahead.
 */
@Slf4j
public final class MilestoneLatcher {

    private MilestoneLatcher() {
    }

    /**
     * Compute the list of step IDs that should be marked completed by milestone auto-latching.
     *
     * @param milestonesFile the loaded milestones file, or {@code null} to skip latching
     * @param guide          the guide
     * @param playerState    current player game state
     * @return ordered list of step IDs from step 1 up to the furthest cleared milestone's
     *         anchor, or an empty list if no milestones are cleared
     */
    public static List<StepId> computeLatchedSteps(
            MilestonesFile milestonesFile,
            StepwiseGuide guide,
            PlayerState playerState) {

        if (milestonesFile == null || playerState == null) {
            return Collections.emptyList();
        }

        Milestone lastCleared = null;
        for (Milestone milestone : milestonesFile.getMilestones()) {
            if (ResolutionEngine.evaluateCondition(milestone.getCriteria(), playerState)) {
                lastCleared = milestone;
                log.debug("Milestone cleared: {} ({})", milestone.getId(), milestone.getLabel());
            } else {
                log.debug("Milestone not cleared: {}", milestone.getId());
            }
        }

        if (lastCleared == null) {
            log.debug("Milestone latch: no milestones cleared, starting from guide beginning");
            return Collections.emptyList();
        }

        StepId anchorId = new StepId(lastCleared.getAnchorStepId());
        List<StepId> latched = new ArrayList<>();
        boolean anchorFound = false;
        for (var step : guide.allSteps()) {
            latched.add(step.getId());
            if (step.getId().equals(anchorId)) {
                anchorFound = true;
                break;
            }
        }

        if (!anchorFound) {
            log.warn("Milestone latch: anchor step '{}' not found in guide, skipping latch",
                    lastCleared.getAnchorStepId());
            return Collections.emptyList();
        }

        log.info("Milestone latch: {} steps fast-forwarded to anchor {} (milestone: {})",
                latched.size(), lastCleared.getAnchorStepId(), lastCleared.getId());
        return latched;
    }
}
