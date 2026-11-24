package com.forgeflow.worker.mcp;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.mcp.client.McpClientManager;
import com.agentsflex.mcp.client.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers MCP tools from the configured servers and classifies them for the approval gate.
 * A server that fails to start is skipped; tools from that server simply do not appear.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.mcp.enabled", havingValue = "true")
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final ObjectMapper objectMapper;
    private final String configResource;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();

    public McpToolRegistry(ObjectMapper objectMapper,
                           @Value("${forgeflow.mcp.config:mcp-servers.json}") String configResource) {
        this.objectMapper = objectMapper;
        this.configResource = configResource;
    }

    @PostConstruct
    void load() {
        try (InputStream is = new ClassPathResource(configResource).getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode servers = root.path("mcpServers");
            if (!servers.isObject()) {
                log.warn("MCP config has no mcpServers object: {}", configResource);
                return;
            }
            servers.fieldNames().forEachRemaining(server -> loadServer(server));
        } catch (Exception e) {
            // MCP is optional: a broken config must not prevent the task service from starting.
            log.warn("MCP tools not loaded from {}: {}", configResource, e.getMessage());
        }
        log.info("MCP tools loaded: {}", tools.keySet());
    }

    private void loadServer(String serverName) {
        try {
            McpClientManager manager = McpClientManager.getInstance();
            McpSyncClient client = manager.getMcpClient(serverName);
            McpSchema.ListToolsResult result = client.listTools();
            for (McpSchema.Tool schema : result.tools()) {
                McpSchema.ToolAnnotations annotations = schema.annotations();
                boolean readOnly = annotations != null
                        && Boolean.TRUE.equals(annotations.readOnlyHint());
                boolean destructive = annotations != null
                        && Boolean.TRUE.equals(annotations.destructiveHint());
                ToolCapability capability = readOnly ? ToolCapability.READ
                        : destructive ? ToolCapability.DANGEROUS : ToolCapability.WRITE;

                Tool tool = manager.getMcpTool(serverName, schema.name());
                if (tools.putIfAbsent(schema.name(), tool) != null) {
                    log.warn("duplicate MCP tool name '{}' from server '{}', keeping first",
                            schema.name(), serverName);
                    continue;
                }
                specs.put(schema.name(), new ToolSpec(schema.name(), capability,
                        !readOnly, null));
                log.info("MCP tool {} capability={} needsConfirm={}",
                        schema.name(), capability, !readOnly);
            }
        } catch (Exception e) {
            log.warn("MCP server '{}' unavailable, tools skipped: {}", serverName, e.getMessage());
        }
    }

    public List<Tool> tools() {
        return new ArrayList<>(tools.values());
    }

    public Map<String, ToolSpec> specs() {
        return Map.copyOf(specs);
    }
}
