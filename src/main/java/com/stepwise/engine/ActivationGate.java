package com.stepwise.engine;

import com.stepwise.guide.StepActivation;
import com.stepwise.model.PlayerState;
import com.stepwise.model.QuestStatus;
import com.stepwise.model.Skill;

/** Evaluates a {@link StepActivation} gate against current game state. */
public final class ActivationGate {

    private ActivationGate() {
    }

    /**
     * True when {@code activation} is satisfied by current game state.
     *
     * <p>Skill levels are base levels: a boost never crosses a gate. Fails closed, so the gated
     * row never appears, on an unrecognised skill name and on {@link StepActivation.Unknown}:
     * a kind the plugin does not recognise, which the loader keeps rather than failing the
     * whole guide.
     */
    public static boolean isOpen(StepActivation activation, PlayerState playerState) {
        if (activation instanceof StepActivation.SkillLevel) {
            StepActivation.SkillLevel sl = (StepActivation.SkillLevel) activation;
            Skill skill = ResolutionEngine.skillOrNull(sl.getSkill());
            return skill != null && playerState.getSkillLevel(skill) >= sl.getLevel();
        }

        if (activation instanceof StepActivation.TotalLevel) {
            return playerState.getTotalLevel() >= ((StepActivation.TotalLevel) activation).getLevel();
        }

        if (activation instanceof StepActivation.QuestComplete) {
            // Resolves the name through the alias table completion conditions use: an
            // unnormalized spelling would miss PlayerState's Quest.getName() keys silently.
            String questName = ((StepActivation.QuestComplete) activation).getQuestName();
            return ResolutionEngine.questStatus(playerState, questName) == QuestStatus.COMPLETED;
        }

        if (activation instanceof StepActivation.HasItem) {
            return hasItemAnywhere(playerState, ((StepActivation.HasItem) activation).getItemName());
        }

        return false;
    }

    /** Whether the named item is carried, worn or banked. */
    static boolean hasItemAnywhere(PlayerState playerState, String targetName) {
        // A gate carries only a name; itemId 0 selects the name match.
        return ResolutionEngine.countItemIn(playerState.getInventory(), 0, targetName)
                + ResolutionEngine.countMatching(playerState.getEquipment().values(), 0, targetName)
                + ResolutionEngine.countItemIn(playerState.getBank(), 0, targetName) > 0;
    }
}
