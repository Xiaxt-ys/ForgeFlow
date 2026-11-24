package com.forgeflow.worker.multiagent;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentTurnOptions;
import com.agentsflex.agent.AgentTurnStatus;
import com.forgeflow.worker.tool.TaskContextHolder;

import java.util.function.Supplier;

/**
 * Real sub-agent: each subtask gets a fresh {@link Agent} (and therefore its own
 * conversation), executed by agents-flex {@link AgentRunner}. Tool permissions are
 * enforced by the Agent's approval policy, so no {@link ToolRouter} is needed here.
 */
public final class RunnerSubAgent implements SubAgent {

    private final Supplier<Agent> agentFactory;
    private final String taskId;
    private final String tenantId;
    private final AgentRunner runner;
    private final boolean streaming;
    private final TurnTracker tracker;

    public RunnerSubAgent(Supplier<Agent> agentFactory, AgentRunner runner) {
        this(agentFactory, null, null, runner, false, noopTracker());
    }

    public RunnerSubAgent(Supplier<Agent> agentFactory, String taskId, String tenantId,
                          AgentRunner runner) {
        this(agentFactory, taskId, tenantId, runner, false, noopTracker());
    }

    public RunnerSubAgent(Supplier<Agent> agentFactory, String taskId, String tenantId,
                          AgentRunner runner, boolean streaming, TurnTracker tracker) {
        this.agentFactory = agentFactory;
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.runner = runner;
        this.streaming = streaming;
        this.tracker = tracker;
    }

    private static TurnTracker noopTracker() {
        return new TurnTracker() {
            @Override
            public void track(String turnId, String taskId, String source) {
            }

            @Override
            public void release(String turnId) {
            }
        };
    }

    @Override
    public SubtaskResult execute(Subtask subtask, AgentContext context, ToolRouter router,
                                 AgentEventBus events) {
        events.emit(new ReasoningDelta(subtask.id(), "start"));
        AgentTurn turn;
        if (taskId != null) {
            TaskContextHolder.set(taskId, tenantId);
        }
        try {
            Agent agent = agentFactory.get();
            if (streaming) {
                AgentTurn started = runner.start(agent, subtask.prompt(),
                        AgentTurnOptions.builder().streaming(true).build());
                tracker.track(started.getId(), taskId, subtask.id());
                try {
                    turn = runner.run(started);
                } finally {
                    tracker.release(started.getId());
                }
            } else {
                turn = runner.run(agent, subtask.prompt());
            }
        } finally {
            if (taskId != null) {
                TaskContextHolder.clear();
            }
        }
        if (turn.getStatus() != AgentTurnStatus.COMPLETED) {
            Throwable error = turn.getError();
            String message = "agent turn " + turn.getStatus()
                    + (error == null ? "" : ": " + error.getMessage());
            return new SubtaskResult(subtask.id(), message, false,
                    turn.getInputTokens(), turn.getOutputTokens(), turn.getStepCount());
        }
        return new SubtaskResult(subtask.id(), turn.getFinalOutput(), true,
                turn.getInputTokens(), turn.getOutputTokens(), turn.getStepCount());
    }
}
