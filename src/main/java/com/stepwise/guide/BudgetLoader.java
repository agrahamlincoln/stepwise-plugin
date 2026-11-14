package com.stepwise.guide;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads {@code guide_budget.json} from plugin resources.
 *
 * <p>Returns {@code null} when the resource is absent, unparseable or empty. Failures log as
 * warnings: the budget drives only the advisory route comparison and its skip offer, and the plugin
 * runs normally without it.
 */
@Slf4j
public final class BudgetLoader {

    private static final String BUDGET_RESOURCE = "/com/stepwise/guide_budget.json";

    private BudgetLoader() {
    }

    public static GuideBudget loadFromResource(Gson gson) {
        return GuideLoader.readResource(BUDGET_RESOURCE, reader -> {
            GuideBudget budget = GuideBudget.freeze(gson.fromJson(reader, GuideBudget.class));
            if (budget == null || budget.getEntries().isEmpty()) {
                log.warn("{} parsed to no ledger rows — route comparison disabled", BUDGET_RESOURCE);
                return null;
            }
            log.debug("Loaded budget ledger: {} entries", budget.getEntries().size());
            return budget;
        }, () -> null);
    }
}
