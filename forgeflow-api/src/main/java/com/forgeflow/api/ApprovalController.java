package com.forgeflow.api;

import com.forgeflow.common.TaskView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolves a task parked in WAITING_APPROVAL.
 *
 * <p>Approval issues a capability token bound to the suspended turn/tool/args and re-queues
 * the task; the worker resumes the same agent turn. Rejection fails the task with
 * {@code APPROVAL_REJECTED}.</p>
 */
@RestController
@RequestMapping("/api/tasks/{id}/approvals")
public class ApprovalController {

    private final TaskService tasks;

    public ApprovalController(TaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping
    public ResponseEntity<TaskService.ApprovalDecision> approve(@PathVariable("id") String taskId) {
        return ResponseEntity.ok(tasks.approve(taskId));
    }

    @PostMapping("/reject")
    public TaskView reject(@PathVariable("id") String taskId,
                           @RequestBody(required = false) RejectRequest request) {
        return tasks.rejectApproval(taskId, request == null ? null : request.reason());
    }

    public record RejectRequest(String reason) {
    }
}
