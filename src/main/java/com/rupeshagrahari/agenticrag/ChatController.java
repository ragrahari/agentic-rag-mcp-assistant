package com.rupeshagrahari.agenticrag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final List<McpSyncClient> mcpSyncClients;
    private final ObjectMapper objectMapper;
    private final TenantRegistryService tenantRegistryService;
    private final CrossTenantReferenceGuardrail crossTenantReferenceGuardrail;
    private final PathRouter pathRouter;

    public ChatController(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory, VectorStore vectorStore,
            RagProperties ragProperties, List<McpSyncClient> mcpSyncClients, ObjectMapper objectMapper,
            TenantRegistryService tenantRegistryService, CrossTenantReferenceGuardrail crossTenantReferenceGuardrail,
            PathRouter pathRouter) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.mcpSyncClients = mcpSyncClients;
        this.objectMapper = objectMapper;
        this.tenantRegistryService = tenantRegistryService;
        this.crossTenantReferenceGuardrail = crossTenantReferenceGuardrail;
        this.pathRouter = pathRouter;

        // Note: QuestionAnswerAdvisor and the MCP tools are deliberately NOT registered
        // here as defaultAdvisors/defaultTools. Both must be scoped to the requesting
        // tenant, which is only known per-request (from the X-Tenant-Id header) -- so
        // fresh, tenant-scoped instances are built in chat() below and attached to that
        // call only.
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request, @RequestHeader("X-Tenant-Id") String tenantId) {
        // M4 part 3: one id per request, threaded explicitly through every call in this
        // method (guardrail, router, capability attachment, retrieval, tool calls) so a
        // single request's log entries can all be found by this value. Passed as a plain
        // parameter rather than via MDC: the actual generation call below is a reactive
        // Flux whose operators (QuestionAnswerAdvisor's retrieval, tool invocation) may
        // run off the request thread, and MDC is thread-local -- it does not reliably
        // survive that handoff without relying on Reactor's automatic context
        // propagation working exactly as expected. Explicit threading has no such
        // dependency.
        String correlationId = UUID.randomUUID().toString();

        // Deterministic guardrail (M4 part 1): a single indexed lookup against the
        // tenants table, before any LLM call, RAG retrieval, MCP discovery, or the router
        // below. Thrown synchronously here -- before any Flux is built -- so Spring
        // resolves it as a normal HTTP error response (403 + reason) rather than
        // something that has to surface mid-stream. This only gates whether the request
        // proceeds at all; which path (RAG/MCP/BOTH/DENY) it takes is the router's job.
        tenantRegistryService.requireActiveTenant(tenantId, correlationId);

        // Deterministic guardrail (M4 part 4): does the message name a different known
        // tenant? Sibling to the check above -- same "throw before any Flux is built"
        // shape, same 403 treatment -- and still entirely before the router or any LLM
        // call. This is a security denial (closes the M2 narration gap), distinct from
        // the router's later quality/off-topic DENY.
        crossTenantReferenceGuardrail.requireNoCrossTenantReference(tenantId, request.message(), correlationId);

        String rawConversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : DEFAULT_CONVERSATION_ID;

        // Memory is namespaced by tenant, not just conversationId. conversationId is
        // client-chosen (a browser-tab UUID); without this, two requests sharing the same
        // conversationId but different X-Tenant-Id headers would read/write the same
        // memory row, leaking one tenant's conversation history into the other's prompt --
        // a real cross-tenant leak vector distinct from (and not covered by) the vector
        // store's tenantId filter below.
        //
        // Hashed into a UUID (not "tenantId::conversationId" concatenation) because
        // SPRING_AI_CHAT_MEMORY.conversation_id is VARCHAR(36) -- sized for a raw UUID by
        // Spring AI's shipped schema. Concatenation overflows that column as soon as
        // tenantId is non-trivial; nameUUIDFromBytes keeps the same 36-char shape while
        // still being deterministic per (tenant, conversationId) pair.
        String scopedConversationId = UUID
                .nameUUIDFromBytes((tenantId + "::" + rawConversationId).getBytes(StandardCharsets.UTF_8))
                .toString();

        // M4 part 2: the router. A separate, focused LLM call classifying the message
        // into RAG/MCP/BOTH/DENY -- runs after the guardrail, before generation. Its
        // decision controls exactly what gets attached to the generation call below
        // (scoped attachment), not just advisory metadata.
        RoutingDecision routingDecision = pathRouter.route(request.message(), correlationId, tenantId);

        if (routingDecision.path() == RoutingPath.DENY) {
            // Skip generation entirely -- this is a quality/scope refusal, not the
            // guardrail's security denial (which never reaches this point at all: it's
            // thrown above, before the router runs, as an HTTP 403). Returned as a normal
            // streamed response so it renders in the chat UI like any assistant reply.
            return Flux.just(routingDecision.reason());
        }

        boolean ragAttached = routingDecision.path() == RoutingPath.RAG || routingDecision.path() == RoutingPath.BOTH;
        boolean mcpAttached = routingDecision.path() == RoutingPath.MCP || routingDecision.path() == RoutingPath.BOTH;
        log.info("event=capabilities_attached correlationId={} tenantId={} path={} ragAttached={} mcpAttached={}",
                correlationId, tenantId, routingDecision.path(), ragAttached, mcpAttached);

        List<Advisor> advisors = new ArrayList<>();
        if (ragAttached) {
            advisors.add(tenantScopedRag(tenantId));
        }
        // Pure observability, attached to every generation call regardless of path --
        // logs retrieved chunks (when RETRIEVED_DOCUMENTS is present in context) and the
        // final assembled prompt. See ChatObservabilityAdvisor for why its ordering
        // guarantees it observes the request only after RAG/memory have already shaped it.
        advisors.add(new ChatObservabilityAdvisor(correlationId, tenantId));

        List<ToolCallback> tools = mcpAttached ? tenantScopedMcpTools(tenantId, correlationId) : List.of();

        return chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.advisors(advisors).param(ChatMemory.CONVERSATION_ID, scopedConversationId))
                .toolCallbacks(tools)
                .stream()
                .content();
    }

    // Built from a trusted server-side value (the header), as a Filter.Expression AST --
    // never as filter text -- so there is no string for a request to inject into. See
    // QuestionAnswerAdvisor's tenant-scoping note in CLAUDE.md. Only constructed for
    // RAG/BOTH routes -- see chat() above.
    private QuestionAnswerAdvisor tenantScopedRag(String tenantId) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(ragProperties.retrieval().topK())
                        .similarityThreshold(ragProperties.retrieval().similarityThreshold())
                        .filterExpression(new FilterExpressionBuilder().eq("tenantId", tenantId).build())
                        .build())
                .build();
    }

    // Built fresh per request rather than as a shared bean: constructing
    // SyncMcpToolCallbackProvider itself is cheap (just wraps the client list), but
    // .getToolCallbacks() below is a live call to the MCP server's list_tools -- if the
    // server is unreachable, it throws. Caught here so a down MCP server means "no
    // live-data tools available for this turn" rather than the whole /chat request
    // failing before generation even starts.
    //
    // Deliberately NOT the auto-configured ToolCallbackProvider bean
    // (spring.ai.mcp.client.toolcallback.enabled=false disables it): its mere presence
    // in the context made an unrelated Ollama-side bean (ToolCallingAutoConfiguration's
    // toolCallbackResolver) eagerly call getToolCallbacks() during application startup,
    // which fails the whole app's startup if the MCP server happens to be down at boot
    // -- proven with a cold-start test. Building the provider here, from the raw client
    // list, keeps that discovery call entirely inside our own request-time try/catch.
    //
    // A tool call failing *after* successful discovery (e.g. server killed
    // mid-conversation) is handled separately, inside TenantScopedToolCallback.
    private List<ToolCallback> tenantScopedMcpTools(String tenantId, String correlationId) {
        ToolCallback[] discovered;
        try {
            discovered = new SyncMcpToolCallbackProvider(mcpSyncClients).getToolCallbacks();
        }
        catch (Exception e) {
            log.warn("event=mcp_discovery_failed correlationId={} tenantId={} error={}", correlationId, tenantId,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return List.of();
        }
        return Arrays.stream(discovered)
                .map(callback -> (ToolCallback) new TenantScopedToolCallback(callback, tenantId, objectMapper,
                        correlationId))
                .toList();
    }

    public record ChatRequest(String message, String conversationId) {
    }

}
