package com.forgeflow.worker.multiagent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One unit of multi-agent work. CAS claim is the fencing: duplicate deliveries
 * race on the same Subtask and only one worker gets to execute it.
 */
public final class Subtask {

    private final String id;
    private final int round;
    private final int index;
    private volatile String prompt;
    private final List<String> dependsOn;
    private final AtomicReference<SubtaskStatus> status =
            new AtomicReference<>(SubtaskStatus.PENDING);
    private final AtomicReference<String> owner = new AtomicReference<>();

    public Subtask(String id, int round, int index, String prompt) {
        this(id, round, index, prompt, List.of());
    }

    public Subtask(String id, String prompt, List<String> dependsOn) {
        this(id, -1, -1, prompt, dependsOn);
    }

    public Subtask(String id, int round, int index, String prompt, List<String> dependsOn) {
        this.id = id;
        this.round = round;
        this.index = index;
        this.prompt = prompt;
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public boolean claim(String workerId) {
        if (!status.compareAndSet(SubtaskStatus.PENDING, SubtaskStatus.CLAIMED)) {
            return false;
        }
        if (!owner.compareAndSet(null, workerId)) {
            status.compareAndSet(SubtaskStatus.CLAIMED, SubtaskStatus.PENDING);
            return false;
        }
        return true;
    }

    public void start() {
        status.set(SubtaskStatus.RUNNING);
    }

    public void succeed() {
        status.set(SubtaskStatus.SUCCEEDED);
    }

    public void fail() {
        status.set(SubtaskStatus.FAILED);
    }

    /** Marks a subtask that never ran because a dependency failed or the task was cancelled. */
    public void skip() {
        status.set(SubtaskStatus.SKIPPED);
    }

    public String id() {
        return id;
    }

    public int round() {
        return round;
    }

    public int index() {
        return index;
    }

    public String prompt() {
        return prompt;
    }

    /** Appends upstream results before the subtask starts; called once by the scheduler. */
    public synchronized void appendPrompt(String suffix) {
        this.prompt = this.prompt + suffix;
    }

    public List<String> dependsOn() {
        return dependsOn;
    }

    public SubtaskStatus status() {
        return status.get();
    }

    public String owner() {
        return owner.get();
    }
}
