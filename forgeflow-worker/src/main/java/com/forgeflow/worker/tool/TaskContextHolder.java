package com.forgeflow.worker.tool;

/**
 * Carries the ForgeFlow task identity into agents-flex's ToolApprovalPolicy and the tool
 * governance interceptors, which only see an AgentTurn or ToolContext.
 */
public final class TaskContextHolder {

    private static final ThreadLocal<String> TASK_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TaskContextHolder() {
    }

    public static void set(String taskId, String tenantId) {
        TASK_ID.set(taskId);
        TENANT_ID.set(tenantId);
    }

    public static void set(String taskId) {
        set(taskId, null);
    }

    public static String get() {
        return TASK_ID.get();
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TASK_ID.remove();
        TENANT_ID.remove();
    }
}
