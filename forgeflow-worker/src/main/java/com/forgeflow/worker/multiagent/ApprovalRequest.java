package com.forgeflow.worker.multiagent;

import java.util.Map;

public record ApprovalRequest(String workspaceId, String subtaskId, String toolName,
                              Map<String, Object> args) {
}
