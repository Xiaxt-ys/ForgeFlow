package com.forgeflow.api;

import com.forgeflow.worker.multiagent.AgentEventBus;
import com.forgeflow.worker.multiagent.MultiAgentOrchestrator;
import com.forgeflow.worker.multiagent.MultiAgentReport;
import com.forgeflow.worker.multiagent.ScriptedSubAgent;
import com.forgeflow.worker.multiagent.ToolRouter;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo endpoint for the multi-agent concurrency harness.
 * Each subtask is delivered twice; CAS claim must execute each one exactly once.
 */
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentController {

    @PostMapping("/run")
    public MultiAgentReport run(@RequestParam(defaultValue = "100") int rounds,
                                @RequestParam(defaultValue = "20") int subtasks,
                                @RequestParam(defaultValue = "4") int concurrency)
            throws InterruptedException {
        if (rounds < 1 || rounds > 1000 || subtasks < 1 || subtasks > 100
                || concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException(
                    "rounds<=1000, subtasks<=100, concurrency<=32");
        }
        ToolRouter router = new ToolRouter(Map.of(
                "search", new ToolSpec("search", ToolCapability.READ, false, null),
                "write_note", new ToolSpec("write_note", ToolCapability.WRITE, true, null),
                "delete", new ToolSpec("delete", ToolCapability.DANGEROUS, true, null)),
                8, request -> true);
        AgentEventBus events = new AgentEventBus();
        try {
            MultiAgentOrchestrator orchestrator =
                    new MultiAgentOrchestrator(new ScriptedSubAgent(0), router, events);
            return orchestrator.run(rounds, subtasks, concurrency);
        } finally {
            events.close();
        }
    }
}
