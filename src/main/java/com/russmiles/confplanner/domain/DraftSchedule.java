package com.russmiles.confplanner.domain;

import java.util.List;

/**
 * The assembled schedule before it has been confirmed as conflict-free.
 *
 * <p>Produced by assembleSchedule; consumed by confirmSchedule. The split exists so the
 * noDoubleBooking invariant can be re-checked as a {@code @Condition} that the goal pre-requires —
 * a clashing draft never satisfies the condition and the planner retries assembly instead of
 * returning a broken schedule.
 */
public record DraftSchedule(List<ScheduleItem> items, String rationale) {}
