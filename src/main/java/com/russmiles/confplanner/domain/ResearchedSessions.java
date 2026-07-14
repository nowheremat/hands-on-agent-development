package com.russmiles.confplanner.domain;

import java.util.List;

/**
 * The shortlist after the research step has annotated each session with relevance reasoning
 * and a 0–1 match score. This replaces {@link CandidateSessions} as the input to assembleSchedule.
 */
public record ResearchedSessions(List<SessionInsight> insights) {}
