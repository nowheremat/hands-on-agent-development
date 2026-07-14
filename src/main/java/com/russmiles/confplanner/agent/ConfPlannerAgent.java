package com.russmiles.confplanner.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.russmiles.confplanner.domain.AttendeeProfile;
import com.russmiles.confplanner.domain.CandidateSessions;
import com.russmiles.confplanner.domain.PersonalSchedule;
import com.russmiles.confplanner.domain.ScheduleItem;
import com.russmiles.confplanner.domain.Session;
import com.russmiles.confplanner.domain.SessionCatalog;
import com.russmiles.confplanner.service.CatalogService;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a free-text attendee request into a conflict-free personal UberConf schedule.
 *
 * <p>The agent is defined as a set of typed {@code @Action}s. It does <em>not</em> hard-wire a
 * sequence: each action declares the types it consumes and the type it produces, and Embabel's
 * planner (GOAP) derives the order. For the baseline the inferred plan reads:
 *
 * <pre>
 *   UserInput
 *     -&gt; extractAttendeeProfile  (LLM)      =&gt; AttendeeProfile
 *     -&gt; loadCatalog             (plain code) =&gt; SessionCatalog
 *     -&gt; shortlistSessions       (LLM)      =&gt; CandidateSessions
 *     -&gt; assembleSchedule        (LLM, goal) =&gt; PersonalSchedule
 * </pre>
 *
 * <p>Run it from the shell with {@code x "I'm a senior platform engineer into Kubernetes,
 * resilience and DevEx; build me a schedule"}.
 *
 * <p>A note on the LLM actions: the model is asked to emit only session <em>ids</em> (plus its
 * reasoning), and plain code resolves those ids back to full {@link Session} records from the
 * catalog. This keeps the model's job small and reliable, and keeps the catalog the single
 * source of truth &mdash; the hybrid AI / plain-code split the workshop keeps returning to.
 */
@Agent(description = "Produce a conflict-free personal UberConf schedule from a free-text request")
public class ConfPlannerAgent {

    private final CatalogService catalogService;

    public ConfPlannerAgent(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** What the model returns when shortlisting: ids it picked, plus why. */
    record Shortlisting(List<String> sessionIds, String reasoning) {
    }

    /** What the model returns when assembling: an ordered, clash-free set of ids, plus the rationale. */
    record ScheduleDraft(List<String> sessionIds, String rationale) {
    }

    // --- 1. Understand the attendee (LLM) -------------------------------------------------

    @Action
    AttendeeProfile extractAttendeeProfile(UserInput userInput, Ai ai) {
        return ai
                .withDefaultLlm()
                .creating(AttendeeProfile.class)
                .fromPrompt("""
                        Read the attendee's request and extract a structured profile.

                        - interests: concrete topics/technologies they mention (lower-case tags)
                        - role: their job role if stated, else a reasonable guess
                        - experienceLevel: one of Beginner, Intermediate, Advanced
                        - goals: what they want out of the conference, as short phrases
                        - avoidTopics: topics/technologies the attendee explicitly wants to avoid (e.g. "no vendor keynotes")

                        # Attendee request
                        %s
                        """.formatted(userInput.getContent()));
    }

    // --- 2. Load the catalog (plain code, no LLM) -----------------------------------------

    @Action
    SessionCatalog loadCatalog(AttendeeProfile profile) {
        // Deterministic: the catalog does not depend on the attendee. We take the profile as
        // input purely so the planner orders this step after the profile exists.
        return catalogService.catalog();
    }

    // --- 3. Shortlist sessions that match the attendee (LLM) ------------------------------

    @Action
    CandidateSessions shortlistSessions(AttendeeProfile profile, SessionCatalog catalog, Ai ai) {
        var filteredMenu = catalog.sessions().stream()
                .filter(s -> !profile.shouldAvoid(s))
                .map(ConfPlannerAgent::menuLine)
                .collect(Collectors.joining("\n"));

        var shortlisting = ai
                .withDefaultLlm()
                .withPromptContributor(profile)
                .creating(Shortlisting.class)
                .fromPrompt("""
                        Pick the 8-14 sessions from the catalog that best match this attendee.
                        Favour their interests, role and goals. Return only the session ids.

                        # Attendee
                        interests: %s
                        role: %s
                        experience: %s
                        goals: %s

                        # Catalog (id: title [tags] (track, level))
                        %s
                        """.formatted(
                        profile.interests(), profile.role(),
                        profile.experienceLevel(), profile.goals(), filteredMenu));

        var chosen = resolve(catalog, shortlisting.sessionIds());
        return new CandidateSessions(chosen);
    }

    // --- 4. Assemble a conflict-free schedule (LLM) — the goal ----------------------------

    @AchievesGoal(description = "Produce a conflict-free personal schedule")
    @Action
    PersonalSchedule assembleSchedule(AttendeeProfile profile, CandidateSessions candidates, Ai ai) {
        var menu = candidates.sessions().stream()
                .map(s -> menuLine(s) + " @ " + s.slot())
                .collect(Collectors.joining("\n"));

        var draft = ai
                .withDefaultLlm()
                .creating(ScheduleDraft.class)
                .fromPrompt("""
                        Build this attendee a personal schedule from the shortlisted sessions.

                        Hard rule: never pick two sessions in the same slot (the "@ <day time>"
                        suffix is the slot). Prefer their stated goals when a slot has a clash.
                        Return the chosen session ids and a short rationale explaining the picks.

                        # Attendee goals
                        %s

                        # Shortlist (id: title [tags] (track, level) @ slot)
                        %s
                        """.formatted(profile.goals(), menu));

        var items = resolve(candidates.sessions(), draft.sessionIds()).stream()
                .map(s -> new ScheduleItem(s, s.slot()))
                .toList();
        return new PersonalSchedule(items, draft.rationale());
    }

    // --- helpers --------------------------------------------------------------------------

    private static String menuLine(Session s) {
        return "%s: %s [%s] (%s, %s)".formatted(
                s.id(), s.title(), String.join(",", s.tags()), s.track(), s.level());
    }

    /** Resolve model-chosen ids back to catalog sessions, ignoring anything it invented. */
    private static List<Session> resolve(SessionCatalog catalog, List<String> ids) {
        return resolve(catalog.sessions(), ids);
    }

    private static List<Session> resolve(List<Session> sessions, List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        Map<String, Session> byId = sessions.stream()
                .collect(Collectors.toMap(Session::id, Function.identity(), (a, b) -> a));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
