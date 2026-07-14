package com.russmiles.confplanner.mock;

import com.embabel.agent.core.Usage;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.loop.LlmMessageResponse;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer;
import com.embabel.chat.AssistantMessageWithToolCalls;
import com.embabel.chat.Message;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.PricingModel;
import com.embabel.common.ai.prompt.PromptContributor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * A deterministic, in-JVM stand-in for a real LLM, active only under the {@code mock} Spring
 * profile. It exists so a learner can run the whole ConfPlanner flow with <em>no API key and no
 * network call</em>: {@code SPRING_PROFILES_ACTIVE=mock ./mvnw spring-boot:run}, then {@code x "..."}
 * produces a real {@code PersonalSchedule}.
 *
 * <p>Why a bean rather than a platform flag? The 0.5.0 property
 * {@code embabel.agent.platform.test.mock-mode} is inert &mdash; no runtime class consults it. The
 * live seam is {@link com.embabel.common.ai.model.ConfigurableModelProvider}, which collects every
 * {@link LlmService} bean from the Spring context. So we register one stub {@code LlmService} named
 * {@code "mock"} and point all model roles at it in {@code application.yml} (under the mock profile).
 *
 * <p>The meat is {@link #createMessageSender}: it returns a sender that concatenates the prompt
 * messages, matches known ConfPlanner prompt fragments, and returns canned JSON text for the type
 * that call expects. Embabel parses that text into the target record &mdash; the sender only needs
 * to match the prompt and return the right JSON. The fragment&rarr;shape mapping mirrors, exactly,
 * the baseline choreography in {@code ConfPlannerAgentIntegrationTest}
 * (extractAttendeeProfile &rarr; shortlistSessions &rarr; assembleSchedule).
 *
 * <p>This bean is {@code @Profile("mock")} only, so the keyless {@code ./mvnw verify} (which does
 * not activate {@code mock}) never sees it and is entirely unaffected.
 */
@Component
@Profile("mock")
public class MockLlmService implements LlmService<MockLlmService> {

    @Override
    public LlmMessageSender createMessageSender(LlmOptions options) {
        return (messages, tools) -> {
            var prompt = messages.stream()
                    .map(Message::getContent)
                    .reduce("", (a, b) -> a + "\n" + b);
            var json = cannedJsonFor(prompt);
            // The tool loop casts the response message to AssistantMessageWithToolCalls; we never
            // call a tool, so it carries the canned text with an empty tool-call list.
            var message = new AssistantMessageWithToolCalls(json, List.of());
            return new LlmMessageResponse(message, json, new Usage(0, 0, null));
        };
    }

    /**
     * Match a known ConfPlanner prompt fragment and return valid JSON for the record that call
     * creates. Same fragments as the Mockito integration tests, but JSON instead of objects. An
     * unmatched prompt yields {@code {}} rather than an error, so a stray call degrades gracefully.
     */
    private String cannedJsonFor(String prompt) {
        // AttendeeProfile(interests, role, experienceLevel, goals, avoidTopics).
        if (prompt.contains("extract a structured profile")) {
            return """
                    {
                      "interests": ["kubernetes", "resilience", "developer-experience"],
                      "role": "Senior Platform Engineer",
                      "experienceLevel": "Advanced",
                      "goals": ["level up platform work"],
                      "avoidTopics": []
                    }
                    """;
        }
        // ConfPlannerAgent.Shortlisting(sessionIds, reasoning) — real catalog ids.
        if (prompt.contains("Pick the 8-14 sessions")) {
            return """
                    {
                      "sessionIds": ["PC-01", "PC-02", "PC-03", "SR-09"],
                      "reasoning": "match interests"
                    }
                    """;
        }
        // ConfPlannerAgent.ResearchOutput — one insight per shortlisted session.
        if (prompt.contains("why it is relevant")) {
            return """
                    {
                      "insights": [
                        {"sessionId": "PC-01", "whyRelevant": "core platform topic", "matchScore": 0.9},
                        {"sessionId": "PC-02", "whyRelevant": "golden paths", "matchScore": 0.8},
                        {"sessionId": "PC-03", "whyRelevant": "cost awareness", "matchScore": 0.7},
                        {"sessionId": "SR-09", "whyRelevant": "resilience patterns", "matchScore": 0.85}
                      ]
                    }
                    """;
        }
        // ConfPlannerAgent.ScheduleDraft(sessionIds, rationale) — PC-01/02/03 sit in three
        // distinct day+time slots, so the picks never clash.
        if (prompt.contains("Build this attendee a personal schedule")) {
            return """
                    {
                      "sessionIds": ["PC-01", "PC-02", "PC-03"],
                      "rationale": "Three platform-leaning sessions across three days, no clashes."
                    }
                    """;
        }
        return "{}";
    }

    @Override
    public LlmMessageStreamer createMessageStreamer(LlmOptions options) {
        throw new UnsupportedOperationException("mock model does not stream");
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public MockLlmService withKnowledgeCutoffDate(LocalDate date) {
        return this;
    }

    @Override
    public MockLlmService withPromptContributor(PromptContributor promptContributor) {
        return this;
    }

    @Override
    public List<PromptContributor> getPromptContributors() {
        return List.of();
    }

    @Override
    public String getName() {
        return "mock";
    }

    @Override
    public String getProvider() {
        return "mock";
    }

    @Override
    public LocalDate getKnowledgeCutoffDate() {
        return LocalDate.of(2026, 1, 1);
    }

    @Override
    public PricingModel getPricingModel() {
        return PricingModel.usdPerToken(0.0, 0.0);
    }
}
