package com.russmiles.confplanner.agent;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.russmiles.confplanner.domain.AttendeeProfile;
import com.russmiles.confplanner.service.CatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for individual actions, with the LLM faked. No Spring context, no API keys:
 * we hand the action a {@link FakeOperationContext} whose next response we queue ourselves,
 * then assert on what the action did with it.
 */
class ConfPlannerAgentTest {

    private final CatalogService catalogService = new CatalogService(new ObjectMapper());
    private final ConfPlannerAgent agent = new ConfPlannerAgent(catalogService);

    @Test
    void extractAttendeeProfilePutsTheRequestInThePrompt() {
        var context = FakeOperationContext.create();
        context.expectResponse(new AttendeeProfile(
                List.of("kubernetes"), "Platform Engineer", "Advanced", List.of("ship faster"), List.of()));

        Ai ai = context.ai();
        agent.extractAttendeeProfile(
                new UserInput("I love Kubernetes and resilience"), ai);

        var prompt = context.getLlmInvocations().getFirst().getMessages().getFirst().getContent();
        assertTrue(prompt.contains("Kubernetes"), "request text should reach the prompt");
        assertTrue(prompt.contains("experienceLevel"), "extraction instructions should be present");
    }

    @Test
    void shortlistResolvesModelChosenIdsToRealSessions() {
        var context = FakeOperationContext.create();
        // The model only emits ids; the action resolves them against the catalog.
        context.expectResponse(new ConfPlannerAgent.Shortlisting(
                List.of("PC-01", "AI-01", "does-not-exist"), "matches interests"));

        var profile = new AttendeeProfile(
                List.of("kubernetes", "ai"), "Engineer", "Intermediate", List.of("learn"), List.of());
        var candidates = agent.shortlistSessions(profile, catalogService.catalog(), context.ai());

        var ids = candidates.sessions().stream().map(s -> s.id()).toList();
        assertEquals(List.of("PC-01", "AI-01"), ids, "unknown ids are dropped, real ones resolved");
    }

    @Test
    void avoidedTopicsAreExcludedFromTheShortlistMenu() {
        var context = FakeOperationContext.create();
        context.expectResponse(new ConfPlannerAgent.Shortlisting(List.of(), "no matches"));

        var profile = new AttendeeProfile(
                List.of("kubernetes"), "Engineer", "Intermediate", List.of("learn"),
                List.of("security"));

        var candidates = agent.shortlistSessions(profile, catalogService.catalog(), context.ai());

        var tags = candidates.sessions().stream()
                .flatMap(s -> s.tags().stream())
                .toList();
        assertTrue(tags.stream().noneMatch(t -> t.equalsIgnoreCase("security")),
                "sessions tagged security should be filtered out before shortlisting");
    }

    @Test
    void shouldAvoidIsCaseInsensitiveOnTags() {
        var profile = new AttendeeProfile(
                List.of(), "Engineer", "Intermediate", List.of(), List.of("Kubernetes"));
        var catalog = catalogService.catalog();
        var kubernetesSession = catalog.sessions().stream()
                .filter(s -> s.tags().stream().anyMatch(t -> t.equalsIgnoreCase("kubernetes")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No kubernetes-tagged session in catalog"));

        assertTrue(profile.shouldAvoid(kubernetesSession),
                "shouldAvoid must match tags case-insensitively");
    }
}
