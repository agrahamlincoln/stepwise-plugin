package com.stepwise.guide;

import com.stepwise.diary.DiaryVarbits;
import com.stepwise.sailing.BoatVarbits;

import java.util.HashSet;
import java.util.Set;

/**
 * The VarPlayer and varbit IDs referenced by guide completion conditions. The login scan reads
 * them so var state set before this session (e.g. a diary task done elsewhere) counts at once,
 * not only after a live change.
 */
public final class RequiredVarIds {
    private final Set<Integer> varpIds;
    private final Set<Integer> varbitIds;

    private RequiredVarIds(Set<Integer> varpIds, Set<Integer> varbitIds) {
        this.varpIds = Set.copyOf(varpIds);
        this.varbitIds = Set.copyOf(varbitIds);
    }

    public Set<Integer> getVarpIds() {
        return varpIds;
    }

    public Set<Integer> getVarbitIds() {
        return varbitIds;
    }

    /**
     * Collect the var IDs referenced by every condition in the guide. A pre-step's var set
     * before login never changes live to tick its checklist row, so pre-steps count too.
     */
    public static RequiredVarIds fromGuide(StepwiseGuide guide) {
        Set<Integer> varps = new HashSet<>();
        Set<Integer> varbits = new HashSet<>();
        for (CompletionCondition cond : guide.leafConditions()) {
            collectFromCondition(cond, varps, varbits);
        }
        return new RequiredVarIds(varps, varbits);
    }

    private static void collectFromCondition(CompletionCondition cond,
                                             Set<Integer> varps, Set<Integer> varbits) {
        if (cond instanceof CompletionCondition.VarpBitValue) {
            varps.add(((CompletionCondition.VarpBitValue) cond).getVarpId());
        } else if (cond instanceof CompletionCondition.VarpValue) {
            varps.add(((CompletionCondition.VarpValue) cond).getVarp());
        } else if (cond instanceof CompletionCondition.VarbitValue) {
            varbits.add(((CompletionCondition.VarbitValue) cond).getVarbitId());
        } else if (cond instanceof CompletionCondition.CombatAchievement) {
            Integer varbitId = ((CompletionCondition.CombatAchievement) cond).getVarbitId();
            if (varbitId != null) {
                varbits.add(varbitId);
            }
        } else if (cond instanceof CompletionCondition.DiaryComplete) {
            // The resolver reads the tier's completion varbit. Claim it so the login scan
            // fetches it.
            CompletionCondition.DiaryComplete d = (CompletionCondition.DiaryComplete) cond;
            Integer varbitId = DiaryVarbits.varbitFor(d.getArea(), d.getTier());
            if (varbitId != null) {
                varbits.add(varbitId);
            }
        } else if (cond instanceof CompletionCondition.DiaryReward) {
            CompletionCondition.DiaryReward d = (CompletionCondition.DiaryReward) cond;
            Integer varbitId = DiaryVarbits.rewardVarbitFor(d.getArea(), d.getTier());
            if (varbitId != null) {
                varbits.add(varbitId);
            }
        } else if (cond instanceof CompletionCondition.BoatOwned) {
            // A boat is named by type and found by slot, so claim every slot's owned and
            // type varbits.
            varbits.addAll(BoatVarbits.ownershipIds());
        } else if (cond instanceof CompletionCondition.BoatComponent) {
            CompletionCondition.BoatComponent b = (CompletionCondition.BoatComponent) cond;
            varbits.addAll(BoatVarbits.ownershipIds());
            varbits.addAll(BoatVarbits.componentIds(b.getComponent()));
        } else if (cond instanceof CompletionCondition.BoatFacility) {
            // A facility's hotspot varbit persists, so a station built earlier reads at login.
            CompletionCondition.BoatFacility f = (CompletionCondition.BoatFacility) cond;
            varbits.addAll(BoatVarbits.ownershipIds());
            varbits.addAll(BoatVarbits.facilityIds(f.getFacility(), BoatVarbits.typeValue(f.getBoatType())));
        }
    }
}
