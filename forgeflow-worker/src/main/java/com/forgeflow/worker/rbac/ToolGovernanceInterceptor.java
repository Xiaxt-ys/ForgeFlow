package com.forgeflow.worker.rbac;

import com.agentsflex.core.model.chat.tool.ToolChain;
import com.agentsflex.core.model.chat.tool.ToolContext;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.forgeflow.worker.compensation.Compensation;
import com.forgeflow.worker.compensation.CompensationTracker;
import com.forgeflow.worker.compensation.Compensator;
import com.forgeflow.worker.sandbox.Sandbox;
import com.forgeflow.worker.tool.TaskContextHolder;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Single gate in front of every tool call:
 *
 * <ol>
 *   <li>resolve the tenant's role and check the tool's capability against it (RBAC);</li>
 *   <li>write an audit line for the decision;</li>
 *   <li>run dangerous tools through the {@link Sandbox} instead of the agent thread.</li>
 * </ol>
 *
 * <p>Tools unknown to the registry are treated as {@code DANGEROUS} by default, so a newly
 * added tool cannot leak through before someone classifies it.</p>
 */
public final class ToolGovernanceInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ToolGovernanceInterceptor.class);

    private final RbacPolicy rbac;
    private final Sandbox sandbox;
    private final Map<String, ToolSpec> specs;
    private final ToolCapability unknownCapability;
    private final CompensationTracker compensationLog;
    private final Map<String, Compensator> compensators;

    public ToolGovernanceInterceptor(RbacPolicy rbac, Sandbox sandbox,
                                     Map<String, ToolSpec> specs,
                                     ToolCapability unknownCapability,
                                     CompensationTracker compensationLog,
                                     Map<String, Compensator> compensators) {
        this.rbac = rbac;
        this.sandbox = sandbox;
        this.specs = Map.copyOf(specs);
        this.unknownCapability = unknownCapability;
        this.compensationLog = compensationLog;
        this.compensators = Map.copyOf(compensators);
    }

    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        String taskId = TaskContextHolder.get();
        String tenantId = TaskContextHolder.getTenantId();
        String tool = context.getTool().getName();
        ToolCapability capability = capabilityOf(tool);
        Role role = rbac.roleFor(tenantId);

        if (!rbac.permits(role, capability)) {
            audit("DENY", taskId, tenantId, role, tool, capability, "role does not allow capability");
            throw new ToolAccessDeniedException(tool, capability, role);
        }
        audit("ALLOW", taskId, tenantId, role, tool, capability, "permitted");

        Object result;
        if (capability == ToolCapability.DANGEROUS) {
            result = sandbox.run(tool, () -> chain.proceed(context));
        } else {
            result = chain.proceed(context);
        }
        if (capability != ToolCapability.READ) {
            recordCompensation(taskId, tool, context.getArgsMap());
        }
        return result;
    }

    private void recordCompensation(String taskId, String tool, Map<String, Object> args) {
        Compensator compensator = compensators.get(tool);
        if (compensator == null) {
            return;
        }
        Compensation compensation = compensator.compensation(taskId, tool, args);
        if (compensation != null) {
            compensationLog.record(taskId, compensation);
            log.info("compensation.recorded task={} tool={} action={}",
                    taskId, tool, compensation.description());
        }
    }

    private ToolCapability capabilityOf(String tool) {
        ToolSpec spec = specs.get(tool);
        return spec == null ? unknownCapability : spec.capability();
    }

    private static void audit(String decision, String taskId, String tenantId, Role role,
                              String tool, ToolCapability capability, String detail) {
        log.info("tool.audit decision={} task={} tenant={} role={} tool={} capability={} detail={}",
                decision, taskId, tenantId, role, tool, capability, detail);
    }
}
