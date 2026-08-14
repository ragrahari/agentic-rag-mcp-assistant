package com.rupeshagrahari.agenticrag;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Structured output of {@link PathRouter}. Parsed via Spring AI's
 * {@code ChatClient.CallResponseSpec#entity(Class)} (backed by {@code BeanOutputConverter}),
 * not free text -- the field descriptions below are included in the JSON-schema format
 * instructions the converter appends to the router prompt.
 */
public record RoutingDecision(
        @JsonPropertyDescription("Exactly one of RAG, MCP, BOTH, or DENY.") RoutingPath path,
        @JsonPropertyDescription("One short sentence explaining why this path was chosen.") String reason) {
}
