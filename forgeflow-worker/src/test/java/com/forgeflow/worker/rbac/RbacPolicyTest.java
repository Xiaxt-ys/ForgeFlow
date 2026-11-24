package com.forgeflow.worker.rbac;

import com.forgeflow.worker.tool.ToolCapability;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RbacPolicyTest {

    private static RbacPolicy policy(String defaultRole, Map<String, String> tenantRoles) {
        RbacPolicy policy = new RbacPolicy();
        policy.setDefaultRole(defaultRole);
        policy.setTenantRoles(tenantRoles);
        return policy;
    }

    @Test
    void viewerOnlyReads() {
        RbacPolicy policy = policy("VIEWER", Map.of());

        assertThat(policy.permits(Role.VIEWER, ToolCapability.READ)).isTrue();
        assertThat(policy.permits(Role.VIEWER, ToolCapability.WRITE)).isFalse();
        assertThat(policy.permits(Role.VIEWER, ToolCapability.DANGEROUS)).isFalse();
    }

    @Test
    void operatorWritesButCannotRunDangerousTools() {
        RbacPolicy policy = policy("OPERATOR", Map.of());

        assertThat(policy.permits(Role.OPERATOR, ToolCapability.WRITE)).isTrue();
        assertThat(policy.permits(Role.OPERATOR, ToolCapability.DANGEROUS)).isFalse();
    }

    @Test
    void adminCanDoEverything() {
        RbacPolicy policy = policy("ADMIN", Map.of());

        assertThat(policy.permits(Role.ADMIN, ToolCapability.DANGEROUS)).isTrue();
    }

    @Test
    void tenantOverridesDefaultRole() {
        RbacPolicy policy = policy("VIEWER", Map.of("tenant-a", "ADMIN"));

        assertThat(policy.roleFor("tenant-a")).isEqualTo(Role.ADMIN);
        assertThat(policy.roleFor("tenant-b")).isEqualTo(Role.VIEWER);
        assertThat(policy.roleFor(null)).isEqualTo(Role.VIEWER);
    }

    @Test
    void unknownRoleFallsBackToDefault() {
        RbacPolicy policy = policy("OPERATOR", Map.of("tenant-a", "SUPERUSER"));

        assertThat(policy.roleFor("tenant-a")).isEqualTo(Role.OPERATOR);
    }

    @Test
    void disabledPolicyAllowsEverything() {
        RbacPolicy policy = policy("VIEWER", Map.of());
        policy.setEnabled(false);

        assertThat(policy.permits(Role.VIEWER, ToolCapability.DANGEROUS)).isTrue();
    }
}
