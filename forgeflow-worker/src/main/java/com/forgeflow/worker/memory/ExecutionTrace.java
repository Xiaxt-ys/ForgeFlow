package com.forgeflow.worker.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layer 3: append-only JSONL execution trace. This is the durable record; live
 * events on the Reactor bus are best-effort.
 */
public final class ExecutionTrace {

    private final Path file;
    private final ObjectMapper mapper;

    public ExecutionTrace(Path workspace, ObjectMapper mapper) {
        this.file = workspace.resolve("memory/trace.jsonl");
        this.mapper = mapper;
    }

    public synchronized void append(String taskId, String event, Map<String, Object> attributes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("taskId", taskId);
        row.put("event", event);
        row.putAll(attributes);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot append execution trace", e);
        }
    }

    public Path file() {
        return file;
    }
}
