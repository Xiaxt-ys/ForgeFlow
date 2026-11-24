package com.forgeflow.worker.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Layer 2: topic -> artifact locator. Keeps the prompt small by pointing at
 * externalized material instead of embedding it.
 */
public final class TopicIndex {

    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, String> topics = new LinkedHashMap<>();

    public TopicIndex(Path workspace, ObjectMapper mapper) {
        this.file = workspace.resolve("memory/topics.json");
        this.mapper = mapper;
        load();
    }

    public synchronized void put(String topic, String locator) {
        topics.put(topic, locator);
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), topics);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write topic index", e);
        }
    }

    public synchronized String get(String topic) {
        return topics.get(topic);
    }

    public synchronized Map<String, String> all() {
        return Map.copyOf(topics);
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            topics.putAll(mapper.readValue(file.toFile(), new TypeReference<Map<String, String>>() {
            }));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read topic index", e);
        }
    }
}
