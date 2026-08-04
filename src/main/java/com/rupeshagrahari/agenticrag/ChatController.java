package com.rupeshagrahari.agenticrag;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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

    public ChatController(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory, VectorStore vectorStore,
            RagProperties ragProperties, List<McpSyncClient> mcpSyncClients, ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.mcpSyncClients = mcpSyncClients;
        this.objectMapper = objectMapper;

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

        // Built from a trusted server-side value (the header), as a Filter.Expression
        // AST -- never as filter text -- so there is no string for a request to inject
        // into. See QuestionAnswerAdvisor's tenant-scoping note in CLAUDE.md.
        QuestionAnswerAdvisor tenantScopedRag = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(ragProperties.retrieval().topK())
                        .similarityThreshold(ragProperties.retrieval().similarityThreshold())
                        .filterExpression(new FilterExpressionBuilder().eq("tenantId", tenantId).build())
                        .build())
                .build();

        return chatClient.prompt()
                .user(request.message())
                .advisors(a -> a.advisors(tenantScopedRag).param(ChatMemory.CONVERSATION_ID, scopedConversationId))
                .toolCallbacks(tenantScopedMcpTools(tenantId))
                .stream()
                .content();
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
    private List<ToolCallback> tenantScopedMcpTools(String tenantId) {
        ToolCallback[] discovered;
        try {
            discovered = new SyncMcpToolCallbackProvider(mcpSyncClients).getToolCallbacks();
        }
        catch (Exception e) {
            log.warn("MCP tool discovery failed (server unavailable?); proceeding without live-data tools", e);
            return List.of();
        }
        return Arrays.stream(discovered)
                .map(callback -> (ToolCallback) new TenantScopedToolCallback(callback, tenantId, objectMapper))
                .toList();
    }

    public record ChatRequest(String message, String conversationId) {
    }

}
