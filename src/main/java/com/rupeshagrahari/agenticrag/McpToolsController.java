package com.rupeshagrahari.agenticrag;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only diagnostic/observability endpoint: what MCP tools does this app currently
 * see? Not tenant-scoped -- this lists tools as registered (including any tenantId
 * parameter a tool declares), the same raw discovery ChatController performs, without
 * TenantScopedToolCallback's schema stripping. Deliberately never errors: a down MCP
 * server is a reportable fact for this endpoint, not a failure.
 */
@RestController
public class McpToolsController {

    private static final Logger log = LoggerFactory.getLogger(McpToolsController.class);

    private final List<McpSyncClient> mcpSyncClients;
    private final ObjectMapper objectMapper;

    public McpToolsController(List<McpSyncClient> mcpSyncClients, ObjectMapper objectMapper) {
        this.mcpSyncClients = mcpSyncClients;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/mcp/tools")
    public McpToolsResponse listTools() {
        ToolCallback[] discovered;
        try {
            discovered = new SyncMcpToolCallbackProvider(mcpSyncClients).getToolCallbacks();
        }
        catch (Exception e) {
            log.warn("MCP tool discovery failed (server unavailable?)", e);
            return new McpToolsResponse(false, List.of());
        }

        List<McpToolInfo> tools = Arrays.stream(discovered)
                .map(ToolCallback::getToolDefinition)
                .map(this::toToolInfo)
                .toList();
        return new McpToolsResponse(true, tools);
    }

    private McpToolInfo toToolInfo(ToolDefinition definition) {
        JsonNode inputSchema;
        try {
            inputSchema = objectMapper.readTree(definition.inputSchema());
        }
        catch (Exception e) {
            inputSchema = NullNode.getInstance();
        }
        return new McpToolInfo(definition.name(), definition.description(), inputSchema);
    }

    public record McpToolsResponse(boolean mcpServerAvailable, List<McpToolInfo> tools) {
    }

    public record McpToolInfo(String name, String description, JsonNode inputSchema) {
    }

}
