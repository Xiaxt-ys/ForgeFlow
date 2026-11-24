package com.forgeflow.api;

import com.forgeflow.common.ResolutionCommand;
import com.forgeflow.common.SubmitTaskCommand;
import com.forgeflow.common.TaskView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<TaskView> submit(@Valid @RequestBody SubmitTaskCommand command,
                                           @RequestHeader(value = "Idempotency-Key", required = false)
                                           String idempotencyKey) {
        TaskView view = taskService.submit(command, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable("id") String id) {
        return taskService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable("id") String id) {
        return taskService.cancel(id);
    }

    @GetMapping("/{id}/result")
    public TaskView result(@PathVariable("id") String id) {
        return taskService.result(id);
    }

    @PostMapping("/{id}/resolution")
    public TaskView resolve(@PathVariable("id") String id,
                            @Valid @RequestBody ResolutionCommand command) {
        return taskService.resolve(id, command);
    }
}
