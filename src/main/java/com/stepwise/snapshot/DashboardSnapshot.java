package com.stepwise.snapshot;

import lombok.Value;
import lombok.Builder;
import java.util.List;

@Value
@Builder
public class DashboardSnapshot {
    String playerName;
    CurrentStepSummary currentStep;
    GuidePosition guidePosition;
    List<DetailedStep> recentCompleted;
    List<DetailedStep> nextDetailed;
    List<NextStep> nextSteps;
    /**
     * Open-ended activities under way that do not hold the active-step pointer: long skill
     * grinds, recurring activities, crop-growth waits. The panel draws them in their own section.
     */
    List<DetailedStep> backgroundSteps;
    /**
     * Where the player stands against the route's plan at the current entry, or null when the
     * bundled guide_budget.json is missing or has no row for it. Advisory: nothing reads this to decide
     * whether a step is done.
     */
    RouteDeficit routeDeficit;
    /**
     * The hardwoods the guide accepts on the Kingdom of Miscellania's hardwood workers at this
     * stage, first choice first, from the last completed step that sets them. Null before any
     * such step is completed.
     */
    List<String> kingdomHardwood;
}
