package com.forgeflow.worker.rbac;

import com.forgeflow.worker.tool.ToolCapability;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Role lookup and capability matrix.
 *
 * <p>The role is resolved from the tenant (config map) because the auth token is not yet
 * part of the task contract. In a real deployment the same policy would take the caller's
 * role from the identity provider; only {@link #roleFor(String)} would change.</p>
 */
@Component
@ConfigurationProperties(prefix = "forgeflow.rbac")
public class RbacPolicy {

    private boolean enabled = true;
    private String defaultRole = "OPERATOR";
    private Map<String, String> tenantRoles = new HashMap<>();

    public Role roleFor(String tenantId) {
        String configured = tenantId == null ? null : tenantRoles.get(tenantId);
        return Role.parse(configured, Role.parse(defaultRole, Role.OPERATOR));
    }

    public boolean permits(Role role, ToolCapability capability) {
        if (!enabled) {
            return true;
        }
        return switch (capability) {
            case READ -> true;
            case WRITE -> role != Role.VIEWER;
            case DANGEROUS -> role == Role.ADMIN;
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(String defaultRole) {
        this.defaultRole = defaultRole;
    }

    public Map<String, String> getTenantRoles() {
        return tenantRoles;
    }

    public void setTenantRoles(Map<String, String> tenantRoles) {
        this.tenantRoles = tenantRoles == null ? new HashMap<>() : new HashMap<>(tenantRoles);
    }
}
