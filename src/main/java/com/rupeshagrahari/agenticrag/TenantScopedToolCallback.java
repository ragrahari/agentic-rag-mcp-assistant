package com.rupeshagrahari.agenticrag;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Wraps an MCP-discovered {@link ToolCallback} to enforce tenant scoping the same way
 * {@code ChatController} scopes RAG retrieval: the tenant id is a trusted server-side
 * value (the request's X-Tenant-Id header), never something the model supplies or can
 * override.
 * <p>
 * Two things happen here, mirroring the RAG filter pattern:
 * <ol>
 * <li>The {@code tenantId} property (if the underlying tool declares one) is stripped
 * from the schema the model sees, so the model is never asked to supply it and has
 * nothing to inject.</li>
 * <li>Every call's arguments have {@code tenantId} forced to the trusted value before
 * delegating -- overwriting anything the model included, not just filling a gap.</li>
 * </ol>
 * Also the last line of defense for MCP unavailability: any failure calling the
 * delegate (connection refused, timeout, protocol error) is caught here and turned into
 * a clean, non-throwing error result, so a down/slow MCP server degrades the
 * conversation instead of the request.
 */
public class TenantScopedToolCallback implements ToolCallback {

    private static final String TENANT_ID_PROPERTY = "tenantId";

    private final ToolCallback delegate;
    private final String tenantId;
    private final ObjectMapper objectMapper;

    public TenantScopedToolCallback(ToolCallback delegate, String tenantId, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.tenantId = tenantId;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition original = delegate.getToolDefinition();
        return DefaultToolDefinition.builder()
                .name(original.name())
                .description(original.description())
                .inputSchema(stripTenantIdProperty(original.inputSchema()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> arguments = StringUtils.hasText(toolInput)
                    ? objectMapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {
                    })
                    : new HashMap<>();
            arguments.put(TENANT_ID_PROPERTY, tenantId);
            return delegate.call(objectMapper.writeValueAsString(arguments));
        }
        catch (Exception e) {
            return "{\"error\":\"The " + delegate.getToolDefinition().name()
                    + " service is currently unavailable. Let the user know their request could not be completed "
                    + "right now and they should try again shortly.\"}";
        }
    }

    private String stripTenantIdProperty(String schemaJson) {
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            if (!(root instanceof ObjectNode objectNode)) {
                return schemaJson;
            }
            JsonNode properties = objectNode.get("properties");
            if (properties instanceof ObjectNode propertiesNode) {
                propertiesNode.remove(TENANT_ID_PROPERTY);
            }
            JsonNode required = objectNode.get("required");
            if (required instanceof ArrayNode requiredArray) {
                ArrayNode filtered = objectMapper.createArrayNode();
                requiredArray.forEach(node -> {
                    if (!node.asText().equals(TENANT_ID_PROPERTY)) {
                        filtered.add(node);
                    }
                });
                objectNode.set("required", filtered);
            }
            return objectMapper.writeValueAsString(objectNode);
        }
        catch (Exception e) {
            // Schema is only advisory to the model; fall back to the unmodified schema
            // rather than fail tool registration over a cosmetic stripping step.
            return schemaJson;
        }
    }

}
