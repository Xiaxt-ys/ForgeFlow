package com.forgeflow.worker.rbac;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolChain;
import com.agentsflex.core.model.chat.tool.ToolContext;
import com.forgeflow.worker.compensation.CompensationLog;
import com.forgeflow.worker.sandbox.Sandbox;
import com.forgeflow.worker.sandbox.SandboxTimeoutException;
import com.forgeflow.worker.tool.TaskContextHolder;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolGovernanceInterceptorTest {

    private static final Map<String, ToolSpec> SPECS = Map.of(
            "search", new ToolSpec("search", ToolCapability.READ, false, null),
            "write_note", new ToolSpec("write_note", ToolCapability.WRITE, true, null),
            "delete", new ToolSpec("delete", ToolCapability.DANGEROUS, true, null));

    private final ToolChain chain = context -> "executed";

    @AfterEach
    void clearContext() {
        TaskContextHolder.clear();
    }

    private static RbacPolicy policy(String role) {
        RbacPolicy policy = new RbacPolicy();
        policy.setDefaultRole(role);
        policy.setTenantRoles(Map.of());
        return policy;
    }

    private ToolContext contextFor(String toolName) {
        Tool named = new Tool() {
            @Override
            public String getName() {
                return toolName;
            }

            @Override
            public String getDescription() {
                return "test tool";
            }

            @Override
            public Parameter[] getParameters() {
                return new Parameter[0];
            }

            @Override
            public Object invoke(Map<String, Object> args) {
                return "executed";
            }
        };
        return new ToolContext(named, new ToolCall("1", toolName, "{\"note\":\"x\"}"));
    }

    @Test
    void viewerCannotCallWriteTool() {
        TaskContextHolder.set("t1", "tenant-a");
        ToolGovernanceInterceptor interceptor = new ToolGovernanceInterceptor(
                policy("VIEWER"), new Sandbox(true, 1_000, "thread", null), SPECS, ToolCapability.DANGEROUS, new CompensationLog(), Map.of());

        assertThatThrownBy(() -> interceptor.intercept(contextFor("write_note"), chain))
                .isInstanceOf(ToolAccessDeniedException.class)
                .hasMessageContaining("VIEWER")
                .hasMessageContaining("WRITE");
    }

    @Test
    void operatorCanCallWriteTool() throws Exception {
        TaskContextHolder.set("t1", "tenant-a");
        ToolGovernanceInterceptor interceptor = new ToolGovernanceInterceptor(
                policy("OPERATOR"), new Sandbox(true, 1_000, "thread", null), SPECS, ToolCapability.DANGEROUS, new CompensationLog(), Map.of());

        assertThat(interceptor.intercept(contextFor("write_note"), chain)).isEqualTo("executed");
    }

    @Test
    void operatorCannotCallDangerousTool() {
        TaskContextHolder.set("t1", "tenant-a");
        ToolGovernanceInterceptor interceptor = new ToolGovernanceInterceptor(
                policy("OPERATOR"), new Sandbox(true, 1_000, "thread", null), SPECS, ToolCapability.DANGEROUS, new CompensationLog(), Map.of());

        assertThatThrownBy(() -> interceptor.intercept(contextFor("delete"), chain))
                .isInstanceOf(ToolAccessDeniedException.class)
                .hasMessageContaining("DANGEROUS");
    }

    @Test
    void adminDangerousCallRunsInsideTheSandbox() {
        TaskContextHolder.set("t1", "tenant-a");
        ToolGovernanceInterceptor interceptor = new ToolGovernanceInterceptor(
                policy("ADMIN"), new Sandbox(true, 100, "thread", null), SPECS, ToolCapability.DANGEROUS, new CompensationLog(), Map.of());
        ToolChain slowChain = context -> {
            LockSupport.parkNanos(2_000_000_000L);
            return "late";
        };

        assertThatThrownBy(() -> interceptor.intercept(contextFor("delete"), slowChain))
                .isInstanceOf(SandboxTimeoutException.class)
                .hasMessageContaining("delete");
    }

    @Test
    void unknownToolFallsBackToTheConfiguredCapability() {
        TaskContextHolder.set("t1", "tenant-a");
        ToolGovernanceInterceptor interceptor = new ToolGovernanceInterceptor(
                policy("VIEWER"), new Sandbox(true, 1_000, "thread", null), SPECS, ToolCapability.DANGEROUS, new CompensationLog(), Map.of());

        assertThatThrownBy(() -> interceptor.intercept(contextFor("mystery"), chain))
                .isInstanceOf(ToolAccessDeniedException.class)
                .hasMessageContaining("DANGEROUS");
    }
}
