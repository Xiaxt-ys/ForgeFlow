package com.forgeflow.worker.tools;

import com.agentsflex.core.model.chat.tool.Tool;
import com.forgeflow.worker.sandbox.Sandbox;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDemoToolsTest {

    private final LocalDemoTools tools = new LocalDemoTools(0, "./target/test-workspace",
            new Sandbox(false, 1_000, "thread", null));

    private Tool tool(String name) {
        return tools.tools().stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void searchFindsSeededNotes() {
        Object result = tool("search").invoke(Map.of("q", "redis"));

        assertThat(result.toString())
                .contains("redis-lease.md")
                .contains("租约");
    }

    @Test
    void searchReportsNoMatchHonestly() {
        Object result = tool("search").invoke(Map.of("q", "zzzx-nonexistent"));

        assertThat(result.toString()).contains("no notes matched");
    }

    @Test
    void writeThenSearchFindsTheNewNote() {
        tool("write_note").invoke(Map.of("note", "quarterly planning notes"));

        assertThat(tool("search").invoke(Map.of("q", "quarterly")).toString())
                .contains("planning notes");
    }

    @Test
    void deleteRemovesTheNoteAndReportsMissingFiles() {
        Object written = tool("write_note").invoke(Map.of("note", "temporary"));
        String path = written.toString().replace("written ", "").split(" ")[0];

        assertThat(tool("delete").invoke(Map.of("path", path)).toString()).contains("deleted");
        assertThat(tool("delete").invoke(Map.of("path", path)).toString()).contains("not found");
    }
}
