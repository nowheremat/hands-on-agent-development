package com.russmiles.confplanner.agent;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.russmiles.confplanner.domain.AttendeeProfile;
import com.russmiles.confplanner.domain.PersonalSchedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end plan, with every LLM step faked. This boots the real Embabel platform with the
 * real catalog loaded, lets the planner derive the order from the types, and runs it &mdash; proving
 * the inferred plan (extract &rarr; loadCatalog &rarr; shortlist &rarr; assemble) actually completes.
 *
 * <p>This is the regression test the later labs must keep green: the schedule flow must survive
 * every extension.
 */
class ConfPlannerAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    @BeforeAll
    static void setUp() {
        System.setProperty("embabel.agent.shell.interactive.enabled", "false");
    }

    @Test
    void producesAScheduleFromAFreeTextRequest() {
        var input = new UserInput(
                "I'm a senior platform engineer into Kubernetes, resilience and DevEx");

        whenCreateObject(prompt -> prompt.contains("extract a structured profile"),
                AttendeeProfile.class)
                .thenReturn(new AttendeeProfile(
                        List.of("kubernetes", "resilience", "developer-experience"),
                        "Senior Platform Engineer", "Advanced",
                        List.of("level up platform work"), List.of()));

        whenCreateObject(prompt -> prompt.contains("Pick the 8-14 sessions"),
                ConfPlannerAgent.Shortlisting.class)
                .thenReturn(new ConfPlannerAgent.Shortlisting(
                        List.of("PC-01", "PC-02", "PC-03", "SR-09"), "match interests"));

        whenCreateObject(prompt -> prompt.contains("why it is relevant"),
                ConfPlannerAgent.ResearchOutput.class)
                .thenReturn(new ConfPlannerAgent.ResearchOutput(List.of(
                        new ConfPlannerAgent.Insight("PC-01", "core platform topic", 0.9),
                        new ConfPlannerAgent.Insight("PC-02", "golden paths", 0.8),
                        new ConfPlannerAgent.Insight("PC-03", "cost awareness", 0.7),
                        new ConfPlannerAgent.Insight("SR-09", "resilience patterns", 0.85))));

        whenCreateObject(prompt -> prompt.contains("Build this attendee a personal schedule"),
                ConfPlannerAgent.ScheduleDraft.class)
                .thenReturn(new ConfPlannerAgent.ScheduleDraft(
                        List.of("PC-01", "PC-02", "PC-03"),
                        "Three platform-leaning sessions across the three days, no clashes."));

        var schedule = AgentInvocation
                .create(agentPlatform, PersonalSchedule.class)
                .invoke(input);

        assertNotNull(schedule);
        assertEquals(3, schedule.items().size(), "all three drafted sessions should be placed");
        assertFalse(schedule.rationale().isBlank(), "schedule must explain itself");
        assertTrue(schedule.getContent().contains("Kubernetes Without the Tears"),
                "rendered schedule should name a chosen session");
    }
}
