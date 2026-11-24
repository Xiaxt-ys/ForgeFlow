package com.forgeflow.worker.tools;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.forgeflow.worker.compensation.Compensation;
import com.forgeflow.worker.compensation.Compensator;
import com.forgeflow.worker.sandbox.Sandbox;
import com.forgeflow.worker.tool.ToolCapability;
import com.forgeflow.worker.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local tools for end-to-end verification without an MCP server.
 *
 * <p>Registered only when {@code forgeflow.tools.local.enabled=true}. The delete tool sleeps
 * {@code delete-sleep-ms} so the sandbox timeout can be exercised deliberately.</p>
 */
@Component
@ConditionalOnProperty(name = "forgeflow.tools.local.enabled", havingValue = "true")
public class LocalDemoTools {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoTools.class);

    private final List<Tool> tools;
    private final Map<String, ToolSpec> specs;
    private final Map<String, Compensator> compensators;
    private final Map<String, String> notes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger noteSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<String> appliedOperations =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Sandbox sandbox;
    private final Path workspace;

    public LocalDemoTools(@Value("${forgeflow.tools.local.delete-sleep-ms:0}") long deleteSleepMs,
                          @Value("${forgeflow.tools.local.workspace:./workspace}") String workspace,
                          Sandbox sandbox) {
        this.sandbox = sandbox;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        notes.put("redis-lease.md",
                "Redis 租约：SET key worker NX PX 30000 加锁；每 10s 续期；"
                        + "释放用 Lua 比对 workerId；DB 版本号做 fencing。");
        notes.put("idempotency.md",
                "提交幂等：Idempotency-Key 加唯一索引；执行幂等：status + version 条件更新。");
        notes.put("outbox.md",
                "Outbox：任务行与待发消息同事务；afterCommit 直接投递，失败由 relay 指数退避重投。");

        this.tools = List.of(
                tool("search", "Search the workspace notes and return matching titles with content.",
                        new Parameter[]{param("q", "keywords to search for")},
                        args -> search(text(args, "q", "query"))),
                tool("write_note", "Write a note to the workspace.",
                        new Parameter[]{param("note", "note content")},
                        args -> writeNote(text(args, "note", "content"))),
                tool("delete", "Delete a workspace file. Dangerous.",
                        new Parameter[]{param("path", "file path to delete")},
                        args -> {
                            sleep(deleteSleepMs);
                            return deleteNote(text(args, "path", "file"));
                        }));

        Map<String, ToolSpec> map = new LinkedHashMap<>();
        map.put("search", new ToolSpec("search", ToolCapability.READ, false, null));
        map.put("write_note", new ToolSpec("write_note", ToolCapability.WRITE, true, null));
        map.put("delete", new ToolSpec("delete", ToolCapability.DANGEROUS, true, null));
        this.specs = Map.copyOf(map);

        // write_note can be undone; delete is irreversible and intentionally has no compensator.
        // The undo is deduplicated by operationId because compensations run at-least-once.
        this.compensators = Map.of("write_note", new Compensator() {
            @Override
            public Compensation compensation(String taskId, String tool, Map<String, Object> args) {
                return compensation(taskId, null, tool, args);
            }

            @Override
            public Compensation compensation(String taskId, String operationId, String tool,
                                             Map<String, Object> args) {
                Object note = args.getOrDefault("note", "");
                String key = operationId == null ? taskId + ":" + note : operationId;
                return new Compensation(operationId, tool, "remove note " + note, () -> {
                    if (!appliedOperations.add(key)) {
                        log.info("compensation.skip task={} op={} already applied", taskId, key);
                        return;
                    }
                    notes.entrySet().removeIf(entry -> entry.getValue().equals(String.valueOf(note)));
                    log.info("compensation.undo task={} tool=write_note note={}", taskId, note);
                }, args);
            }
        });
    }

    public Map<String, Compensator> compensators() {
        return compensators;
    }

    public List<Tool> tools() {
        return tools;
    }

    public Map<String, ToolSpec> specs() {
        return specs;
    }

    private static Tool tool(String name, String description, Parameter[] parameters,
                             java.util.function.Function<Map<String, Object>, Object> action) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Parameter[] getParameters() {
                return parameters;
            }

            @Override
            public Object invoke(Map<String, Object> args) {
                return action.apply(args == null ? Map.of() : args);
            }
        };
    }

    private String search(Object rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.toString().trim();
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            return "no query given; workspace has " + notes.size() + " notes: "
                    + String.join(", ", notes.keySet());
        }
        List<String> hits = notes.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(needle)
                        || entry.getValue().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (hits.isEmpty()) {
            return "no notes matched '" + query + "' (workspace has " + notes.size() + " notes)";
        }
        StringBuilder result = new StringBuilder("matched " + hits.size() + " notes:");
        for (String hit : hits) {
            result.append("\n- ").append(hit).append(": ").append(notes.get(hit));
        }
        return result.toString();
    }

    private String writeNote(Object raw) {
        String content = raw == null ? "" : raw.toString();
        String path = "note-" + noteSeq.incrementAndGet() + ".md";
        notes.put(path, content);
        if (sandbox.isolated()) {
            try {
                Files.createDirectories(workspace);
                Files.writeString(workspace.resolve(path), content);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot write sandbox workspace", failure);
            }
        }
        return "written " + path + " (" + content.length() + " chars)";
    }

    private String deleteNote(Object rawPath) {
        String path = safeName(rawPath);
        if (path.isEmpty()) {
            return "no valid path given";
        }
        String removed = notes.remove(path);
        if (sandbox.isolated()) {
            try {
                String output = sandbox.runCommand("delete",
                        "rm -f /work/" + path + " && echo container-deleted " + path);
                return output.contains("container-deleted") ? output : "container output: " + output;
            } catch (Exception failure) {
                throw new IllegalStateException("container sandbox failed: " + failure.getMessage(),
                        failure);
            }
        }
        return removed == null ? "not found: " + path : "deleted " + path;
    }

    private static String safeName(Object rawPath) {
        String name = rawPath == null ? "" : rawPath.toString().trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "";
        }
        return name;
    }

    private static Parameter param(String name, String description) {
        Parameter parameter = new Parameter();
        parameter.setName(name);
        parameter.setType("string");
        parameter.setDescription(description);
        parameter.setRequired(true);
        return parameter;
    }

    /** Reads the first present key; the model may use either the declared name or a synonym. */
    private static Object text(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value != null) {
                return value;
            }
        }
        return "";
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
