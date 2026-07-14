package com.russmiles.confplanner.domain;

/**
 * A shortlisted session enriched with a relevance explanation and a match score.
 *
 * <p>Produced by the research step; consumed by assembleSchedule so the model
 * can make score-informed picks rather than choosing blindly from raw session data.
 */
public record SessionInsight(
        Session session,
        String whyRelevant,
        double matchScore
) {}
