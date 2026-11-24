package com.forgeflow.worker.mcp;

import com.agentsflex.agent.tool.ToolApprovalDecision;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolApprovalPolicyTest {

    private final McpToolApprovalPolicy failClosed = new McpToolApprovalPolicy(Map.of(
            "search", new ToolSpec("search", ToolCapability.READ, false, null),
            "refund", new ToolSpec("refund", ToolCapability.WRITE, true, null),
            "drop", new ToolSpec("drop", ToolCapability.DANGEROUS, true, null)),
            false);

    private final McpToolApprovalPolicy autoApprove = new McpToolApprovalPolicy(Map.of(
            "refund", new ToolSpec("refund", ToolCapability.WRITE, true, null)),
            true);

    @Test
    void readToolIsAllowed() {
        assertThat(decide(failClosed, "search"))
                .isEqualTo(ToolApprovalDecision.Outcome.ALLOW);
    }

    @Test
    void writeToolSuspendsForApprovalWithoutToken() {
        assertThat(decide(failClosed, "refund"))
                .isEqualTo(ToolApprovalDecision.Outcome.REQUIRE_APPROVAL);
    }

    @Test
    void dangerousToolSuspendsForApprovalWithoutToken() {
        assertThat(decide(failClosed, "drop"))
                .isEqualTo(ToolApprovalDecision.Outcome.REQUIRE_APPROVAL);
    }

    @Test
    void autoApproveOnlyAffectsConfiguredDevMode() {
        assertThat(decide(autoApprove, "refund"))
                .isEqualTo(ToolApprovalDecision.Outcome.ALLOW);
    }

    @Test
    void localToolIsAllowed() {
        assertThat(decide(failClosed, "knowledge_search"))
                .isEqualTo(ToolApprovalDecision.Outcome.ALLOW);
    }

    private ToolApprovalDecision.Outcome decide(McpToolApprovalPolicy policy, String toolName) {
        return policy.decide(null, null, tool(toolName)).getOutcome();
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Parameter[] getParameters() {
                return new Parameter[0];
            }

            @Override
            public Object invoke(Map<String, Object> argsMap) {
                return null;
            }
        };
    }
}
