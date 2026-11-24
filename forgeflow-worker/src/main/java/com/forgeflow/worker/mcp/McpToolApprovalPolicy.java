package com.forgeflow.worker.mcp;

import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.agent.tool.ToolApprovalPolicy;
import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Tool;
import com.forgeflow.worker.tool.ApprovalTokenStore;
import com.forgeflow.worker.tool.CanonicalJson;
import com.forgeflow.worker.tool.TaskContextHolder;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fail-closed policy for MCP tools:
 * - read-only tools run directly;
 * - write/dangerous tools are denied unless explicitly auto-approved in a dev profile.
 *
 * The DENY path is deliberate: a missing approval must not silently execute a side effect.
 * A later slice can replace DENY with REQUIRE_APPROVAL and resume the AgentTurn after a token is issued.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.mcp.enabled", havingValue = "true")
public class McpToolApprovalPolicy implements ToolApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(McpToolApprovalPolicy.class);

    private final Map<String, ToolSpec> specs;
    private final ApprovalTokenStore tokenStore;
    private final boolean autoApproveWrites;

    @Autowired
    public McpToolApprovalPolicy(McpToolRegistry registry, ApprovalTokenStore tokenStore,
                                 @Value("${forgeflow.mcp.auto-approve-writes:false}")
                                 boolean autoApproveWrites) {
        this(registry.specs(), tokenStore, autoApproveWrites);
    }

    McpToolApprovalPolicy(Map<String, ToolSpec> specs, boolean autoApproveWrites) {
        this(specs, null, autoApproveWrites);
    }

    McpToolApprovalPolicy(Map<String, ToolSpec> specs, ApprovalTokenStore tokenStore,
                          boolean autoApproveWrites) {
        this.specs = Map.copyOf(specs);
        this.tokenStore = tokenStore;
        this.autoApproveWrites = autoApproveWrites;
    }

    @Override
    public ToolApprovalDecision decide(AgentTurn turn, ToolCall toolCall, Tool tool) {
        ToolSpec spec = specs.get(tool.getName());
        if (spec == null) {
            // A local tool (e.g. knowledge search) is governed elsewhere.
            return ToolApprovalDecision.ALLOW;
        }
        if (spec.capability() == ToolCapability.READ) {
            audit("ALLOW", "MCP_READ_ALLOWED", tool.getName(), spec.capability());
            return ToolApprovalDecision.allow()
                    .code("MCP_READ_ALLOWED")
                    .reason("read-only MCP tool")
                    .build();
        }
        if (autoApproveWrites) {
            audit("ALLOW", "MCP_WRITE_AUTO_APPROVED", tool.getName(), spec.capability());
            return ToolApprovalDecision.allow()
                    .code("MCP_WRITE_AUTO_APPROVED")
                    .reason("dev auto-approval enabled")
                    .build();
        }
        String taskId = TaskContextHolder.get();
        String turnId = turn == null ? null : turn.getId();
        String argsHash = CanonicalJson.of(toolCall == null || toolCall.getArgsMap() == null
                ? Map.of() : toolCall.getArgsMap());
        if (tokenStore != null && taskId != null && turnId != null
                && tokenStore.verify(taskId, turnId, tool.getName(), argsHash)) {
            audit("ALLOW", "MCP_WRITE_APPROVED", tool.getName(), spec.capability());
            return ToolApprovalDecision.allow()
                    .code("MCP_WRITE_APPROVED")
                    .reason("capability token matches task/turn/tool/args")
                    .build();
        }
        audit("REQUIRE_APPROVAL", "MCP_WRITE_REQUIRES_APPROVAL", tool.getName(), spec.capability());
        return ToolApprovalDecision.requireApproval()
                .code("MCP_WRITE_REQUIRES_APPROVAL")
                .message("Write tool '" + tool.getName() + "' requires an approval token.")
                .reason("fail-closed: no capability token bound to this task/step/args")
                .build();
    }

    private static void audit(String decision, String code, String tool, ToolCapability capability) {
        log.info("approval.audit decision={} code={} task={} tenant={} tool={} capability={}",
                decision, code, TaskContextHolder.get(), TaskContextHolder.getTenantId(),
                tool, capability);
    }
}
