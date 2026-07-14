package com.russmiles.confplanner.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.mcpserver.security.SecureAgentTool;
import com.russmiles.confplanner.domain.AttendeeProfile;
import com.russmiles.confplanner.domain.CandidateSessions;
import com.russmiles.confplanner.domain.DraftSchedule;
import com.russmiles.confplanner.domain.PersonalSchedule;
import com.russmiles.confplanner.domain.PremiumBriefing;
import com.russmiles.confplanner.domain.ResearchedSessions;
import com.russmiles.confplanner.domain.ScheduleItem;
import com.russmiles.confplanner.domain.Session;
import com.russmiles.confplanner.domain.SessionCatalog;
import com.russmiles.confplanner.domain.SessionInsight;
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
 * planner (GOAP) derives the order. The inferred plan reads:
 *
 * <pre>
 *   UserInput
 *     -&gt; extractAttendeeProfile  (LLM)       =&gt; AttendeeProfile
 *     -&gt; loadCatalog             (plain code) =&gt; SessionCatalog
 *     -&gt; shortlistSessions       (LLM)       =&gt; CandidateSessions  [post: hasCandidates]
 *     -&gt; researchSessions        (LLM)       =&gt; ResearchedSessions
 *     -&gt; assembleSchedule        (LLM)       =&gt; DraftSchedule      [post: noDoubleBooking]
 *     -&gt; confirmSchedule         (code, goal) =&gt; PersonalSchedule  [pre: noDoubleBooking]
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

    /** One researched insight as the model emits it (id-only; code resolves the Session). */
    record Insight(String sessionId, String whyRelevant, double matchScore) {
    }

    /** What the model returns when researching: one insight per shortlisted id. */
    record ResearchOutput(List<Insight> insights) {
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
                        - avoidTopics: topics/tags the attendee explicitly does NOT want
                          (e.g. "no vendor keynotes" -> ["vendor", "keynote"]); empty if none

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

    @Action(post = {"hasCandidates"})
    CandidateSessions shortlistSessions(AttendeeProfile profile, SessionCatalog catalog, Ai ai) {
        // Belt: drop avoided sessions in plain code so the rule holds even if the model slips.
        var menu = catalog.sessions().stream()
                .filter(s -> !profile.shouldAvoid(s))
                .map(ConfPlannerAgent::menuLine)
                .collect(Collectors.joining("\n"));

        // Braces: attach the profile as a PromptContributor so its avoid-list rides along in
        // the prompt automatically — the rule lives on the domain object (DICE), not here.
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
                        profile.experienceLevel(), profile.goals(), menu));

        var chosen = resolve(catalog, shortlisting.sessionIds());
        return new CandidateSessions(chosen);
    }

    // --- 3b. Research the shortlist (LLM) — added in Lab 2 --------------------------------

    @Action
    ResearchedSessions researchSessions(CandidateSessions candidates, Ai ai) {
        var menu = candidates.sessions().stream()
                .map(ConfPlannerAgent::menuLine)
                .collect(Collectors.joining("\n"));

        var output = ai
                .withDefaultLlm()
                .creating(ResearchOutput.class)
                .fromPrompt("""
                        For each shortlisted session, say in one sentence why it is relevant to an
                        attendee and give a matchScore between 0.0 and 1.0. Return one entry per id.

                        # Shortlist (id: title [tags] (track, level))
                        %s
                        """.formatted(menu));

        var byId = candidates.sessions().stream()
                .collect(Collectors.toMap(Session::id, Function.identity(), (a, b) -> a));
        var insights = (output.insights() == null ? List.<Insight>of() : output.insights()).stream()
                .filter(i -> byId.containsKey(i.sessionId()))
                .map(i -> new SessionInsight(byId.get(i.sessionId()), i.whyRelevant(), i.matchScore()))
                .toList();
        return new ResearchedSessions(insights);
    }

    // --- Conditions (side-effect-free invariants the planner re-checks each cycle) ----------

    @Condition(name = "hasCandidates")
    boolean hasCandidates(CandidateSessions candidates) {
        return candidates != null && !candidates.sessions().isEmpty();
    }

    @Condition(name = "noDoubleBooking")
    boolean noDoubleBooking(DraftSchedule draft) {
        var slots = draft.items().stream().map(ScheduleItem::slot).toList();
        return slots.size() == new java.util.HashSet<>(slots).size();
    }

    // --- 4. Assemble a draft schedule (LLM) -----------------------------------------------

    @Action(pre = {"hasCandidates"}, post = {"noDoubleBooking"}, canRerun = true)
    DraftSchedule assembleSchedule(AttendeeProfile profile, ResearchedSessions researched, Ai ai) {
        var menu = researched.insights().stream()
                .map(i -> menuLine(i.session()) + " @ " + i.session().slot()
                        + " — score " + i.matchScore() + " — " + i.whyRelevant())
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

                        # Shortlist (id: title [tags] (track, level) @ slot — score — why)
                        %s
                        """.formatted(profile.goals(), menu));

        var sessions = researched.insights().stream().map(SessionInsight::session).toList();
        var items = resolve(sessions, draft.sessionIds()).stream()
                .map(s -> new ScheduleItem(s, s.slot()))
                .toList();
        return new DraftSchedule(items, draft.rationale());
    }

    // --- 5. Confirm the draft (plain code, goal) — only runs if noDoubleBooking holds ------

    @AchievesGoal(description = "Produce a conflict-free personal schedule")
    @Action(pre = {"noDoubleBooking"})
    PersonalSchedule confirmSchedule(DraftSchedule draft) {
        return new PersonalSchedule(draft.items(), draft.rationale());
    }

    // --- 6. Premium briefing (secured — never scheduled by GOAP, MCP-only) ----------------

    @SecureAgentTool("hasAuthority('conf:premium')")
    PremiumBriefing premiumBriefing(ResearchedSessions researched, Ai ai) {
        var summary = ai
                .withDefaultLlm()
                .creating(String.class)
                .fromPrompt("""
                        Write a two-sentence premium briefing for a senior platform engineer
                        based on these researched sessions.

                        # Sessions
                        %s
                        """.formatted(researched.insights().stream()
                        .map(i -> i.session().title() + " — " + i.whyRelevant())
                        .collect(Collectors.joining("\n"))));
        return new PremiumBriefing(summary);
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
