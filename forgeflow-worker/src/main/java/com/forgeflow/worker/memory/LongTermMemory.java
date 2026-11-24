package com.forgeflow.worker.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1: durable facts. Small, human-readable, append-oriented.
 */
public final class LongTermMemory {

    private final Path file;

    public LongTermMemory(Path workspace) {
        this.file = workspace.resolve("memory/MEMORY.md");
    }

    public synchronized void appendFact(String fact) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "- " + fact + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write long-term memory", e);
        }
    }

    public synchronized List<String> facts() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> facts = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("- ")) {
                    facts.add(line.substring(2));
                }
            }
            return facts;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read long-term memory", e);
        }
    }

    public Path file() {
        return file;
    }
}
