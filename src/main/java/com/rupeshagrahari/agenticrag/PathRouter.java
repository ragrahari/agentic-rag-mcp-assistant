package com.rupeshagrahari.agenticrag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Service;

/**
 * M4 part 2: the path router. A separate, focused LLM call that classifies the user's
 * message into exactly one of RAG/MCP/BOTH/DENY -- run after the tenant guardrail
 * ({@link TenantRegistryService}) passes and before the main generation call in
 * {@link ChatController}. The router's own DENY is a quality/scope decision (off-topic,
 * nonsensical) -- unrelated to the guardrail's security DENY (unknown/inactive tenant)
 * and to the not-yet-built cross-tenant-reference / prompt-injection guardrails.
 */
@Service
public class PathRouter {

    private static final Logger log = LoggerFactory.getLogger(PathRouter.class);

    private static final String ROUTER_SYSTEM_PROMPT = """
            You are the path router for a corporate travel-and-card fintech assistant. Classify the
            user's message into exactly one path. Do not answer the question -- only classify it.

            - RAG: answerable from internal policy or product documentation (travel policy, card
              program rules, per-diem limits, expense categorization). No live account data needed.
              Examples: "What's our per-diem limit for international travel?"
              "How do I categorize a hotel booking that includes a conference fee?"
              "What does our travel policy say about booking outside the approved platform?"

            - MCP: needs live account or card data (balance, a specific transaction, transaction
              status) and does not require interpreting it against policy.
              Examples: "What's the current balance on my corporate card?"
              "Show me my last five transactions."
              "Is transaction #4821 still pending?"

            - BOTH: needs live account/card data interpreted through policy -- asks whether something
              that happened is allowed, compliant, or within a limit.
              Examples: "I spent $340 on dinner last night -- is that within policy?"
              "Was my flight booking compliant with our travel rules?"

            - DENY: off-topic, nonsensical, or unrelated to corporate travel/card/expense assistance.
              This is a quality/scope denial only -- it is not a security decision. Tenant identity and
              access have already been checked before you saw this message; do not deny for
              tenant-related reasons, only for topic/quality reasons.
              Examples: "Tell me a joke."
              "What's the weather like today?"
              "asdkfj alksdjf laksjdf"

            Always choose exactly one path. reason must be one short sentence explaining the choice.
            """;

    private final ChatClient routerChatClient;

    public PathRouter(ChatClient.Builder chatClientBuilder) {
        // Deliberately bare: no default advisors (no chat memory, no RAG, no MCP tools).
        // The router classifies a single message in isolation -- it doesn't need
        // conversation history to do that, and keeping this client minimal is what keeps
        // the call small and fast, per the M4 router spec.
        this.routerChatClient = chatClientBuilder.build();
    }

    public RoutingDecision route(String userMessage, String correlationId, String tenantId) {
        try {
            RoutingDecision decision = routerChatClient.prompt()
                    .system(ROUTER_SYSTEM_PROMPT)
                    .user(userMessage)
                    // Ollama's grammar-constrained "format": "json" guarantees syntactically
                    // valid JSON output at the decoding level. Combined with the JSON-schema
                    // format instructions ChatClient's .entity() appends to the prompt (which
                    // describe the RoutingDecision shape), this is what makes the structured
                    // output reliably parseable rather than hoping a general-purpose local
                    // model happens to emit clean JSON. temperature(0.0) removes sampling
                    // variance from a task that should be deterministic given the same input.
                    .options(OllamaOptions.builder().format("json").temperature(0.0).build())
                    .call()
                    .entity(RoutingDecision.class);

            if (decision == null || decision.path() == null) {
                throw new IllegalStateException("Router returned a null decision or path");
            }
            // The user's raw message is deliberately not logged here -- only the
            // classification outcome. Free-text "reason" is the router's own short
            // sentence, not user input, so it's safe at INFO.
            log.info("event=router_decision correlationId={} tenantId={} path={} reason={}", correlationId,
                    tenantId, decision.path(), decision.reason());
            return decision;
        }
        catch (Exception e) {
            // Covers both a down/erroring model call and a structured-output parse
            // failure (malformed or schema-mismatched JSON) -- entity() throws for either.
            // Fail open to BOTH, not DENY: BOTH is a superset of every other path's
            // capabilities (RAG context + MCP tools both attached), so a misrouted request
            // still has everything it needs to answer correctly -- it just loses the
            // "distraction removal" benefit of scoped attachment for this one turn. DENY
            // is a real user-facing refusal and a routing hiccup is not grounds for one.
            log.warn("event=router_decision correlationId={} tenantId={} path={} reason={} error={}", correlationId,
                    tenantId, RoutingPath.BOTH, "router call/parse failed, defaulting to BOTH",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new RoutingDecision(RoutingPath.BOTH,
                    "Routing classification failed; defaulting to full context as a safe fallback.");
        }
    }

}
