package com.forgeflow.worker;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentResumeCommand;
import com.agentsflex.agent.AgentResumeCommandType;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentRunnerOptions;
import com.agentsflex.agent.AgentSuspension;
import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentTurnOptions;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.agent.event.AgentEventType;
import com.agentsflex.agent.loader.AgentLoader;
import com.agentsflex.agent.store.AgentTurnStore;
import com.agentsflex.agent.store.InMemoryAgentTurnStore;
import com.agentsflex.agent.store.jdbc.JdbcAgentStoreConfig;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.prompt.SimplePrompt;
import com.forgeflow.common.AgentExecutor;
import com.forgeflow.common.AgentRequest;
import com.forgeflow.common.AgentResult;
import com.forgeflow.common.ExecutionContext;
import com.forgeflow.common.TaskEvent;
import com.forgeflow.store.ApprovalRecord;
import com.forgeflow.store.ApprovalRepository;
import com.forgeflow.store.TaskEventBus;
import com.forgeflow.worker.context.ToolResultLimiter;
import com.forgeflow.worker.mcp.McpToolApprovalPolicy;
import com.forgeflow.worker.mcp.McpToolRegistry;
import com.forgeflow.worker.model.RoutingChatModel;
import com.forgeflow.worker.loop.LoopGuard;
import com.forgeflow.worker.loop.LoopGuardChatModel;
import com.forgeflow.worker.loop.LoopGuardToolInterceptor;
import com.forgeflow.worker.rbac.RbacPolicy;
import com.forgeflow.worker.rbac.ToolGovernanceInterceptor;
import com.forgeflow.worker.sandbox.Sandbox;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import com.forgeflow.worker.tools.LocalDemoTools;
import com.forgeflow.worker.memory.SessionMemoryService;
import com.forgeflow.worker.multiagent.AgentEventBus;
import com.forgeflow.worker.compensation.CompensationTracker;
import com.forgeflow.worker.compensation.Compensator;
import com.forgeflow.worker.multiagent.DagOrchestrator;
import com.forgeflow.worker.multiagent.InvalidAgentOutputException;
import com.forgeflow.worker.multiagent.LlmTaskPlanner;
import com.forgeflow.worker.multiagent.OrchestrationResult;
import com.forgeflow.worker.multiagent.OutputValidator;
import com.forgeflow.worker.multiagent.ResultAggregator;
import com.forgeflow.worker.multiagent.RunnerSubAgent;
import com.forgeflow.worker.multiagent.TaskPlan;
import com.forgeflow.worker.multiagent.TaskPlanStrategy;
import com.forgeflow.worker.multiagent.TaskPlanner;
import com.forgeflow.worker.multiagent.TurnTracker;
import com.forgeflow.worker.tool.TaskContextHolder;
import com.forgeflow.worker.tool.CanonicalJson;
import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Uses agents-flex as the agent engine: AgentRunner owns the ReAct turn, tool calling,
 * approval and snapshot state. ForgeFlow owns the task lifecycle and fencing around it.
 *
 * <p>The entry agent ({@link TaskPlanner}) decides SIMPLE vs COMPLEX. A complex prompt is
 * split into a dependency graph and executed by {@link DagOrchestrator}; the results are
 * merged by {@link ResultAggregator} back into one task result.</p>
 */
@Component
@ConditionalOnProperty(name = "forgeflow.agent.executor", havingValue = "agents-flex")
public class AgentsFlexAgentExecutor implements AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentsFlexAgentExecutor.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectProvider<RoutingChatModel> routingModelProvider;
    private final ObjectProvider<McpToolRegistry> mcpRegistryProvider;
    private final ObjectProvider<McpToolApprovalPolicy> mcpApprovalProvider;
    private final ObjectProvider<LocalDemoTools> localToolsProvider;
    private final int toolResultMaxChars;
    private final TaskPlanStrategy planner;
    private final boolean llmAggregator;
    private final ObjectProvider<SessionMemoryService> sessionMemory;
    private final boolean orchestrationEnabled;
    private final int orchestrationConcurrency;
    private final long orchestrationTimeoutMs;
    private final DagOrchestrator orchestrator = new DagOrchestrator();
    private final ResultAggregator aggregator = new ResultAggregator();
    private final OutputValidator validator;
    private final LoopGuard loopGuard;
    private final RbacPolicy rbacPolicy;
    private final Sandbox sandbox;
    private final ToolCapability unknownToolCapability;
    private final CompensationTracker compensationLog;
    private final ApprovalRepository approvals;
    private final TaskEventBus events;
    private final boolean streamingEnabled;
    private final ObjectProvider<javax.sql.DataSource> dataSourceProvider;
    private final String turnStoreMode;
    private final String turnStorePrefix;
    private final String turnStoreBinary;
    private final java.util.concurrent.ConcurrentHashMap<String, Tracked> turnTasks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private AgentTurnStore turnStore = new InMemoryAgentTurnStore();
    private final ExecutorService callPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ff-agent-call");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Agent agent;
    private final AgentRunner runner;

    public AgentsFlexAgentExecutor(ObjectProvider<ChatModel> chatModelProvider,
                                   ObjectProvider<RoutingChatModel> routingModelProvider,
                                   ObjectProvider<McpToolRegistry> mcpRegistryProvider,
                                   ObjectProvider<McpToolApprovalPolicy> mcpApprovalProvider,
                                   ObjectProvider<LocalDemoTools> localToolsProvider,
                                   @Value("${forgeflow.agent.tool-result-max-chars:4000}")
                                   int toolResultMaxChars,
                                   @Value("${forgeflow.orchestration.enabled:true}")
                                   boolean orchestrationEnabled,
                                   @Value("${forgeflow.orchestration.simple-max-chars:200}")
                                   int simpleMaxChars,
                                   @Value("${forgeflow.orchestration.max-subtasks:8}")
                                   int maxSubtasks,
                                   @Value("${forgeflow.orchestration.clarify-threshold:0.5}")
                                   double clarifyThreshold,
                                   @Value("${forgeflow.orchestration.concurrency:4}")
                                   int orchestrationConcurrency,
                                   @Value("${forgeflow.orchestration.timeout-ms:300000}")
                                   long orchestrationTimeoutMs,
                                   @Value("${forgeflow.orchestration.max-output-chars:20000}")
                                   int maxOutputChars,
                                   @Value("${forgeflow.orchestration.validate-json:true}")
                                   boolean validateJson,
                                   @Value("${forgeflow.orchestration.planner:rule}")
                                   String plannerMode,
                                   @Value("${forgeflow.orchestration.aggregator:concat}")
                                   String aggregatorMode,
                                   ObjectProvider<SessionMemoryService> sessionMemory,
                                   @Value("${forgeflow.loop.enabled:true}")
                                   boolean loopGuardEnabled,
                                   @Value("${forgeflow.loop.max-steps:20}")
                                   int loopMaxSteps,
                                   @Value("${forgeflow.loop.repeat-threshold:3}")
                                   int loopRepeatThreshold,
                                   @Value("${forgeflow.loop.stable-state-threshold:4}")
                                   int loopStableStateThreshold,
                                   @Value("${forgeflow.rbac.unknown-capability:DANGEROUS}")
                                   String unknownCapability,
                                   RbacPolicy rbacPolicy,
                                   Sandbox sandbox,
                                   CompensationTracker compensationLog,
                                   ApprovalRepository approvals,
                                   TaskEventBus events,
                                   @Value("${forgeflow.streaming.enabled:false}")
                                   boolean streamingEnabled,
                                   ObjectProvider<javax.sql.DataSource> dataSourceProvider,
                                   @Value("${forgeflow.agent.turn-store:memory}")
                                   String turnStoreMode,
                                   @Value("${forgeflow.agent.turn-store-prefix:ff_agent_}")
                                   String turnStorePrefix,
                                   @Value("${forgeflow.agent.turn-store-binary:BLOB}")
                                   String turnStoreBinary) {
        this.chatModelProvider = chatModelProvider;
        this.routingModelProvider = routingModelProvider;
        this.mcpRegistryProvider = mcpRegistryProvider;
        this.mcpApprovalProvider = mcpApprovalProvider;
        this.localToolsProvider = localToolsProvider;
        this.toolResultMaxChars = toolResultMaxChars;
        TaskPlanner rulePlanner = new TaskPlanner(simpleMaxChars, maxSubtasks, clarifyThreshold);
        this.planner = "llm".equalsIgnoreCase(plannerMode)
                ? new LlmTaskPlanner(this::plannerModel, rulePlanner, maxSubtasks, clarifyThreshold)
                : rulePlanner;
        this.llmAggregator = "llm".equalsIgnoreCase(aggregatorMode);
        this.sessionMemory = sessionMemory;
        this.orchestrationEnabled = orchestrationEnabled;
        this.orchestrationConcurrency = orchestrationConcurrency;
        this.orchestrationTimeoutMs = orchestrationTimeoutMs;
        this.validator = new OutputValidator(maxOutputChars, validateJson);
        this.loopGuard = new LoopGuard(loopGuardEnabled, loopMaxSteps,
                loopRepeatThreshold, loopStableStateThreshold);
        this.rbacPolicy = rbacPolicy;
        this.sandbox = sandbox;
        this.compensationLog = compensationLog;
        this.approvals = approvals;
        this.events = events;
        this.streamingEnabled = streamingEnabled;
        this.dataSourceProvider = dataSourceProvider;
        this.turnStoreMode = turnStoreMode;
        this.turnStorePrefix = turnStorePrefix;
        this.turnStoreBinary = turnStoreBinary;
        this.unknownToolCapability = ToolCapability.valueOf(
                unknownCapability.trim().toUpperCase(java.util.Locale.ROOT));
        this.runner = buildRunner();
    }

    @Override
    public AgentResult execute(AgentRequest request, ExecutionContext context) {
        try {
            AgentResult result = executeInternal(request, context);
            // The turn finished; recorded side effects are final and must not be undone later.
            compensationLog.clear(request.taskId());
            return result;
        } catch (RuntimeException failure) {
            List<String> undone = compensationLog.compensateAll(request.taskId());
            if (!undone.isEmpty()) {
                log.warn("compensation.done task={} actions={}", request.taskId(), undone);
            }
            throw failure;
        }
    }

    private AgentResult executeInternal(AgentRequest request, ExecutionContext context) {
        if (context.cancelled()) {
            throw new IllegalStateException("cancelled before agent turn");
        }
        loopGuard.reset(request.taskId());
        SessionMemoryService memory = sessionMemory.getIfAvailable();
        String sessionContext = memory == null ? "" : memory.contextFor(request.taskId(), request.prompt());
        TaskPlan plan = planner.plan(request.prompt(), sessionContext);
        if (orchestrationEnabled && plan.isComplex()) {
            return executePlan(plan, request, context);
        }

        ApprovalRecord approved = approvals.findApproved(request.taskId()).orElse(null);
        String agentPrompt = plan.subtasks().isEmpty()
                ? request.prompt() : plan.subtasks().get(0).prompt();
        AgentTurn turn = approved != null
                ? resumeApproved(approved, request, context)
                : runInterruptibly(agent(), agentPrompt, request.taskId(),
                        request.tenantId(), context);
        if (turn.getStatus() == AgentTurnStatus.WAITING_FOR_APPROVAL) {
            throw parkForApproval(turn, request);
        }
        if (turn.getStatus() != AgentTurnStatus.COMPLETED) {
            Throwable error = turn.getError();
            throw new IllegalStateException("agent turn ended with " + turn.getStatus()
                    + (error == null ? "" : ": " + error.getMessage()), error);
        }
        if (context.cancelled()) {
            throw new IllegalStateException("cancelled after agent turn");
        }
        validateOrHandoff(null, turn.getFinalOutput());
        AgentResult result = new AgentResult(
                turn.getFinalOutput(),
                turn.getInputTokens(),
                turn.getOutputTokens(),
                turn.getStepCount(),
                Map.of("agentTurnId", turn.getId(), "agentStatus", turn.getStatus().name(),
                        "mode", "SIMPLE", "route", plan.route()));
        if (memory != null) {
            memory.rememberAsync(request.taskId(), request.prompt(), result.output());
        }
        return result;
    }

    private AgentResult executePlan(TaskPlan plan, AgentRequest request, ExecutionContext context) {
        OrchestrationResult result;
        try (AgentEventBus events = new AgentEventBus()) {
            // No ToolRouter: sub-agents call tools through the Agent's approval policy.
            result = orchestrator.run(plan,
                    new RunnerSubAgent(this::buildAgent, request.taskId(), request.tenantId(),
                            runner, streamingEnabled, new TurnTracker() {
                                @Override
                                public void track(String turnId, String subtaskTaskId, String source) {
                                    AgentsFlexAgentExecutor.this.track(turnId, subtaskTaskId, source);
                                }

                                @Override
                                public void release(String turnId) {
                                    AgentsFlexAgentExecutor.this.release(turnId);
                                }
                            }),
                    null, events, context, orchestrationConcurrency, orchestrationTimeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("plan interrupted", interrupted);
        }

        if (result.cancelled()) {
            throw new IllegalStateException("plan cancelled");
        }
        if (result.timedOut()) {
            throw new IllegalStateException("plan timed out after " + orchestrationTimeoutMs + "ms");
        }
        if (result.failed() > 0 || result.skipped() > 0) {
            throw new IllegalStateException("plan incomplete: " + result.succeeded() + "/"
                    + plan.subtasks().size() + " subtasks succeeded");
        }
        for (com.forgeflow.worker.multiagent.SubtaskResult subtaskResult : result.results()) {
            validateOrHandoff(subtaskResult.subtaskId(), subtaskResult.output());
        }

        ResultAggregator.Aggregate aggregate = aggregator.aggregate(plan, result,
                llmAggregator ? this::synthesize : null);
        log.info("plan.aggregated task={} subtasks={} aggregator={}", request.taskId(),
                plan.subtasks().size(), aggregate.metadata().get("aggregator"));
        validateOrHandoff(null, aggregate.output());
        Map<String, Object> metadata = aggregate.metadata();
        SessionMemoryService memory = sessionMemory.getIfAvailable();
        if (memory != null) {
            memory.rememberAsync(request.taskId(), request.prompt(), aggregate.output());
        }
        return new AgentResult(
                aggregate.output(),
                number(metadata.get("promptTokens")),
                number(metadata.get("completionTokens")),
                (int) number(metadata.get("steps")),
                metadata);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** The same model used by the agent, resolved lazily so a missing bean is tolerated. */
    private ChatModel plannerModel() {
        RoutingChatModel routing = routingModelProvider.getIfAvailable();
        return routing != null ? routing : chatModelProvider.getIfUnique();
    }

    /** Exit-agent call: merge subtask outputs into one answer, non-thinking and deterministic. */
    private String synthesize(String prompt) {
        ChatModel model = plannerModel();
        if (model == null) {
            throw new IllegalStateException("no ChatModel available for aggregation");
        }
        ChatOptions options = new ChatOptions();
        options.setTemperature(0f);
        options.setThinkingEnabled(false);
        return model.chat(prompt, options);
    }

    /** An unusable model output is not a crash: park the task for a human instead. */
    private void validateOrHandoff(String subtaskId, String output) {
        try {
            validator.validate(subtaskId, output);
        } catch (InvalidAgentOutputException invalid) {
            throw new HumanHandoffRequiredException(
                    "output validation failed: " + invalid.getMessage(), invalid);
        }
    }

    /**
     * agents-flex executes turns and tools on its own pool, so the task identity has to be
     * copied onto those threads or audit/RBAC/approval see a null task. The same runner
     * forwards streaming deltas onto the task event bus.
     */
    private AgentRunner buildRunner() {
        ExecutorService turnPool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ff-agent-turn");
            thread.setDaemon(true);
            return thread;
        });
        com.forgeflow.worker.tool.TaskContextExecutor propagating =
                new com.forgeflow.worker.tool.TaskContextExecutor(turnPool);
        this.turnStore = buildTurnStore();
        AgentLoader loader = new AgentLoader() {
            @Override
            public Agent load(String agentId, String version) {
                return buildAgent();
            }

            @Override
            public Agent loadActive(String agentId) {
                return buildAgent();
            }
        };
        AgentRunner built = AgentRunner.builder()
                .turnStore(turnStore)
                .agentLoader(loader)
                .options(AgentRunnerOptions.builder()
                        .toolExecutor(propagating)
                        .modelExecutor(propagating)
                        .build())
                .build();
        built.addEventListener(event -> {
            AgentEventType type = event.getType();
            if (type != AgentEventType.MODEL_TEXT_DELTA
                    && type != AgentEventType.MODEL_REASONING_DELTA) {
                return;
            }
            Tracked tracked = turnTasks.get(event.getTurnId());
            if (tracked == null) {
                return;
            }
            Object content = event.getData() == null ? null : event.getData().get("content");
            if (content == null) {
                return;
            }
            events.publish(type == AgentEventType.MODEL_TEXT_DELTA
                    ? TaskEvent.delta(tracked.taskId(), tracked.source(), String.valueOf(content))
                    : TaskEvent.reasoning(tracked.taskId(), tracked.source(), String.valueOf(content)));
        });
        return built;
    }

    /**
     * Vertical JDBC store when configured: the turn survives the worker that created it, so
     * approval resume is no longer pinned to one process. The in-memory store keeps local
     * runs and tests dependency-free.
     */
    private AgentTurnStore buildTurnStore() {
        if (!"jdbc".equalsIgnoreCase(turnStoreMode)) {
            log.info("agent turn store: in-memory");
            return new InMemoryAgentTurnStore();
        }
        javax.sql.DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException("turn-store=jdbc but no DataSource is available");
        }
        JdbcAgentStoreConfig config = JdbcAgentStoreConfig.builder(dataSource)
                .tablePrefix(turnStorePrefix)
                .binaryColumnType(turnStoreBinary)
                .build();
        config.schema().initialize();
        log.info("agent turn store: jdbc prefix={} binary={}", turnStorePrefix, turnStoreBinary);
        return config.turnStore();
    }

    private void track(String turnId, String taskId, String source) {
        if (turnId != null && taskId != null) {
            turnTasks.put(turnId, new Tracked(taskId, source));
        }
    }

    private void release(String turnId) {
        if (turnId != null) {
            turnTasks.remove(turnId);
        }
    }

    private record Tracked(String taskId, String source) {
    }

    /**
     * Runs one agent turn on a helper thread so a user cancel can interrupt it. The
     * cancellation flag is polled every 200ms; an uncooperative HTTP client is abandoned
     * rather than allowed to block the worker.
     */
    /**
     * Persists the suspended tool call and asks the worker to park the task in
     * WAITING_APPROVAL. The turn itself stays in the runner's turn store.
     */
    private ApprovalPendingException parkForApproval(AgentTurn turn, AgentRequest request) {
        AgentSuspension suspension = turn.getSuspension();
        String tool = suspension == null ? "unknown" : suspension.getToolName();
        String callId = suspension == null ? "unknown" : suspension.getCorrelationId();
        String argsJson = suspension == null ? "{}" : suspension.getArguments();
        Map<String, Object> args;
        try {
            args = JSON.parseObject(argsJson == null ? "{}" : argsJson, Map.class);
        } catch (RuntimeException malformed) {
            args = Map.of();
        }
        approvals.insert(request.taskId(), turn.getId(), callId, tool,
                CanonicalJson.of(args), argsJson);
        log.info("approval.required task={} turn={} tool={} call={}",
                request.taskId(), turn.getId(), tool, callId);
        return new ApprovalPendingException(tool, turn.getId(), callId);
    }

    /** Continues a turn that was suspended for tool approval. */
    private AgentTurn resumeApproved(ApprovalRecord approval, AgentRequest request,
                                     ExecutionContext context) {
        if (context.cancelled()) {
            throw new IllegalStateException("cancelled before approval resume");
        }
        approvals.deleteByTask(request.taskId());
        TaskContextHolder.set(request.taskId(), request.tenantId());
        track(approval.turnId(), request.taskId(), null);
        try {
            AgentTurn turn = runner.resume(approval.turnId(), new AgentResumeCommand(
                    AgentResumeCommandType.APPROVE_TOOL, "approved", approval.callId(),
                    Map.of("taskId", request.taskId())));
            log.info("approval.resumed task={} turn={} tool={}",
                    request.taskId(), approval.turnId(), approval.tool());
            return turn;
        } finally {
            release(approval.turnId());
            TaskContextHolder.clear();
        }
    }

    private AgentTurn runInterruptibly(Agent target, String prompt, String taskId, String tenantId,
                                       ExecutionContext context) {
        AgentTurnOptions options = AgentTurnOptions.builder().streaming(streamingEnabled).build();
        Future<AgentTurn> future = callPool.submit(() -> {
            // The turn runs on this thread, so the task binding must be set here.
            TaskContextHolder.set(taskId, tenantId);
            try {
                AgentTurn started = runner.start(target, prompt, options);
                track(started.getId(), taskId, null);
                try {
                    return runner.run(started);
                } finally {
                    release(started.getId());
                }
            } finally {
                TaskContextHolder.clear();
            }
        });
        try {
            while (true) {
                if (context.cancelled()) {
                    future.cancel(true);
                    throw new IllegalStateException("cancelled during agent turn");
                }
                try {
                    return future.get(200, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillRunning) {
                    // keep polling the cancellation flag
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during agent turn", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("agent turn failed", cause);
        }
    }

    @PreDestroy
    void shutdown() {
        callPool.shutdownNow();
    }

    private Agent agent() {
        Agent current = agent;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (agent == null) {
                agent = buildAgent();
            }
            return agent;
        }
    }

    private Agent buildAgent() {
        RoutingChatModel routing = routingModelProvider.getIfAvailable();
        ChatModel model = routing != null ? routing : chatModelProvider.getIfUnique();
        if (model == null) {
            throw new IllegalStateException(
                    "no agents-flex ChatModel bean; configure one or use forgeflow.agent.executor=echo");
        }
        Agent.Builder builder = Agent.builder("forgeflow-worker")
                .version("1")
                .instructions("You are a ForgeFlow task worker. Complete the user's task. "
                        + "Use tools when needed; never claim a side effect you did not perform.")
                .chatModel(new LoopGuardChatModel(model, loopGuard));
        McpToolRegistry registry = mcpRegistryProvider.getIfAvailable();
        LocalDemoTools localTools = localToolsProvider.getIfAvailable();
        Map<String, ToolSpec> toolSpecs = new java.util.LinkedHashMap<>();
        if (registry != null) {
            toolSpecs.putAll(registry.specs());
        }
        if (localTools != null) {
            toolSpecs.putAll(localTools.specs());
        }
        Map<String, Compensator> compensators = localTools == null
                ? Map.of() : localTools.compensators();
        builder.toolInterceptor(new ToolGovernanceInterceptor(
                        rbacPolicy, sandbox, toolSpecs, unknownToolCapability,
                        compensationLog, compensators))
                .toolInterceptor(new ToolResultLimiter(toolResultMaxChars))
                .toolInterceptor(new LoopGuardToolInterceptor(loopGuard));
        List<com.agentsflex.core.model.chat.tool.Tool> allTools = new java.util.ArrayList<>();
        if (registry != null) {
            allTools.addAll(registry.tools());
        }
        if (localTools != null) {
            allTools.addAll(localTools.tools());
        }
        if (!allTools.isEmpty()) {
            builder.tools(allTools);
        }
        McpToolApprovalPolicy approval = mcpApprovalProvider.getIfAvailable();
        if (approval != null) {
            builder.toolApprovalPolicy(approval);
        }
        return builder.build();
    }
}
