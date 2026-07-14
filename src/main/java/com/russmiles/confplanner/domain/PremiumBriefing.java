package com.russmiles.confplanner.domain;

/**
 * A premium attendee briefing produced by a secured action.
 *
 * <p>The goal never consumes this type, so the GOAP planner never schedules the action that
 * produces it — it is only reachable via an authenticated MCP tool call.
 */
public record PremiumBriefing(String summary) {}
