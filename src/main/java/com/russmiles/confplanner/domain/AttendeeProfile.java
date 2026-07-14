package com.russmiles.confplanner.domain;

import com.embabel.common.ai.prompt.PromptContributor;

import java.util.List;

/**
 * What we know about the attendee we are planning for.
 *
 * <p>This is the first thing the agent produces from free text, and the lens through
 * which every later step is judged: which sessions to shortlist, how to resolve clashes,
 * what to say in the rationale. Modelling the attendee explicitly &mdash; rather than passing
 * a bag of strings around &mdash; is the "Model the domain first" habit (DICE) in practice.
 *
 * <p>Lab 1 evolves this record: it gains {@code avoidTopics} and starts contributing to the
 * prompt itself (see {@code PromptContributor}). In the baseline it is a plain data carrier.
 */
public record AttendeeProfile(
        List<String> interests,
        String role,
        String experienceLevel,
        List<String> goals,
        List<String> avoidTopics
) implements PromptContributor {

    @Override
    public String contribution() {
        if (avoidTopics == null || avoidTopics.isEmpty()) {
            return "";
        }
        return "The attendee wants to AVOID these topics: " + String.join(", ", avoidTopics)
                + ". Never recommend a session tagged with them.";
    }

    public boolean shouldAvoid(Session session) {
        if (avoidTopics == null || avoidTopics.isEmpty()) {
            return false;
        }
        return session.tags().stream()
                .anyMatch(tag -> avoidTopics.stream()
                        .anyMatch(avoid -> avoid.equalsIgnoreCase(tag)));
    }
}
