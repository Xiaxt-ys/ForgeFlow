package com.forgeflow.worker;

import com.forgeflow.worker.multiagent.AgentEventBus;
import com.forgeflow.worker.multiagent.MultiAgentOrchestrator;
import com.forgeflow.worker.multiagent.MultiAgentReport;
import com.forgeflow.worker.multiagent.ScriptedSubAgent;
import com.forgeflow.worker.multiagent.ToolRouter;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentOrchestratorTest {

    @Test
    void twoThousandSubtasksWithDuplicateDeliveryHaveNoDuplicatesOrOmissions() throws Exception {
        ToolRouter router = new ToolRouter(Map.of(
                "search", new ToolSpec("search", ToolCapability.READ, false, null),
                "write_note", new ToolSpec("write_note", ToolCapability.WRITE, true, null)),
                8, request -> true);
        AgentEventBus events = new AgentEventBus();
        MultiAgentOrchestrator orchestrator =
                new MultiAgentOrchestrator(new ScriptedSubAgent(0), router, events);

        MultiAgentReport report = orchestrator.run(100, 20, 4);
        events.close();

        assertThat(report.total()).isEqualTo(2_000);
        assertThat(report.claimed()).isEqualTo(2_000);
        assertThat(report.executed()).isEqualTo(2_000);
        assertThat(report.succeeded()).isEqualTo(2_000);
        assertThat(report.duplicates()).isZero();
        assertThat(report.omissions()).isZero();
        assertThat(report.rejectedAttempts()).isEqualTo(2_000);
    }
}
