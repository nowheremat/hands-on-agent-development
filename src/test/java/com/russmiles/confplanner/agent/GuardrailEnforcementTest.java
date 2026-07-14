package com.russmiles.confplanner.agent;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.Budget;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.russmiles.confplanner.domain.AttendeeProfile;
import com.russmiles.confplanner.domain.PersonalSchedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves that a double-booked draft never reaches the goal.
 *
 * <p>The mock draft places two sessions in the same slot (PC-01 and AI-01 both sit at
 * "2026-09-15 09:00" in the synthetic catalog). The {@code noDoubleBooking} condition fails,
 * so {@code confirmSchedule} cannot run. The planner retries {@code assembleSchedule}
 * (canRerun=true) and eventually hits the budget, throwing rather than returning a clash.
 */
class GuardrailEnforcementTest extends EmbabelMockitoIntegrationTest {

    @BeforeAll
    static void setUp() {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
    }

    @Test
    void doubleBookedDraftNeverReachesTheGoal() {
        var input = new UserInput(
                "I'm a senior platform engineer into Kubernetes, resilience and DevEx");

        whenCreateObject(prompt -> prompt.contains("extract a structured profile"),
                AttendeeProfile.class)
                .thenReturn(new AttendeeProfile(
                        List.of("kubernetes", "resilience"),
                        "Senior Platform Engineer", "Advanced",
                        List.of("level up platform work"), List.of()));

        whenCreateObject(prompt -> prompt.contains("Pick the 8-14 sessions"),
                ConfPlannerAgent.Shortlisting.class)
                .thenReturn(new ConfPlannerAgent.Shortlisting(
                        List.of("PC-01", "AI-01"), "match interests"));

        whenCreateObject(prompt -> prompt.contains("why it is relevant"),
                ConfPlannerAgent.ResearchOutput.class)
                .thenReturn(new ConfPlannerAgent.ResearchOutput(List.of(
                        new ConfPlannerAgent.Insight("PC-01", "core platform topic", 0.9),
                        new ConfPlannerAgent.Insight("AI-01", "ai fundamentals", 0.7))));

        // Force a clash: PC-01 and AI-01 both sit at "2026-09-15 09:00" in the catalog.
        whenCreateObject(prompt -> prompt.contains("Build this attendee a personal schedule"),
                ConfPlannerAgent.ScheduleDraft.class)
                .thenReturn(new ConfPlannerAgent.ScheduleDraft(
                        List.of("PC-01", "AI-01"), "intentional clash for test"));

        var options = ProcessOptions.DEFAULT.withBudget(new Budget(0.50, 10, 200_000));

        assertThrows(Exception.class, () ->
                AgentInvocation
                        .builder(agentPlatform)
                        .options(options)
                        .build(PersonalSchedule.class)
                        .invoke(input),
                "a double-booked draft must never reach the goal — the run should throw");
    }
}
