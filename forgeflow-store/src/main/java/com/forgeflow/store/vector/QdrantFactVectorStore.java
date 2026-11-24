package com.forgeflow.store.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qdrant-backed ANN index ({@code forgeflow.memory.vector-store=qdrant}).
 *
 * <p>Collections are per embedding model and dimension, because Qdrant fixes the vector size at
 * collection creation: switching the model creates a new collection instead of mixing spaces.
 * Old collections are left in place (they are cheap and act as a rollback path).</p>
 *
 * <p>Every call is best-effort from the caller's point of view: the store falls back to the
 * in-memory cosine scan when Qdrant is unreachable.</p>
 */
@Component
@ConditionalOnProperty(name = "forgeflow.memory.vector-store", havingValue = "qdrant")
public class QdrantFactVectorStore implements FactVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantFactVectorStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int COLLECTION_NAME_LIMIT = 200;

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient client;
    private final java.util.Set<String> ensured = ConcurrentHashMap.newKeySet();

    public QdrantFactVectorStore(
            @Value("${forgeflow.memory.qdrant.base-url:http://localhost:6333}") String baseUrl,
            @Value("${forgeflow.memory.qdrant.api-key:}") String apiKey,
            @Value("${forgeflow.memory.qdrant.timeout-ms:3000}") long timeoutMs) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(Math.max(200, timeoutMs));
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        log.info("memory.vector-store qdrant url={}", this.baseUrl);
    }

    @Override
    public void index(String model, int dim, String sessionId, String factId, String factKey,
                      float[] vector) {
        String collection = collection(model, dim);
        ensureCollection(collection, dim);
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", factId);
        point.put("vector", toList(vector));
        point.put("payload", Map.of("session_id", sessionId, "fact_key", factKey));
        JsonNode response = call("PUT", "/collections/" + collection + "/points?wait=true",
                Map.of("points", List.of(point)));
        if (response == null) {
            throw new IllegalStateException("qdrant upsert returned no response");
        }
    }

    @Override
    public List<String> search(String model, int dim, String sessionId, float[] query, int limit) {
        String collection = collection(model, dim);
        ensureCollection(collection, dim);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", toList(query));
        body.put("limit", Math.max(1, limit));
        body.put("with_payload", false);
        body.put("filter", sessionFilter(sessionId));
        JsonNode response = call("POST", "/collections/" + collection + "/points/search", body);
        List<String> ids = new ArrayList<>();
        if (response != null) {
            for (JsonNode hit : response.path("result")) {
                String id = hit.path("id").asText(null);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    @Override
    public long count(String model, int dim, String sessionId) {
        String collection = collection(model, dim);
        ensureCollection(collection, dim);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", sessionFilter(sessionId));
        body.put("exact", false);
        JsonNode response = call("POST", "/collections/" + collection + "/points/count", body);
        return response == null ? 0L : response.path("result").path("count").asLong(0);
    }

    /** Exposed for tests and for operators reading startup logs. */
    public String collectionName(String model, int dim) {
        return collection(model, dim);
    }

    private void ensureCollection(String collection, int dim) {
        if (ensured.contains(collection)) {
            return;
        }
        synchronized (ensured) {
            if (ensured.contains(collection)) {
                return;
            }
            JsonNode existing = call("GET", "/collections/" + collection, null);
            if (existing == null) {
                Map<String, Object> vectors = new LinkedHashMap<>();
                vectors.put("size", dim);
                vectors.put("distance", "Cosine");
                JsonNode created = call("PUT", "/collections/" + collection,
                        Map.of("vectors", vectors));
                if (created == null) {
                    throw new IllegalStateException("qdrant create collection failed: " + collection);
                }
                log.info("memory.vector-store created collection={} dim={}", collection, dim);
                // Filtering by session is the hot path; index it best-effort.
                try {
                    call("PUT", "/collections/" + collection + "/index",
                            Map.of("field_name", "session_id", "field_schema", "keyword"));
                } catch (RuntimeException ignored) {
                    // Older Qdrant versions infer it; not fatal either way.
                }
            }
            ensured.add(collection);
        }
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            if (!apiKey.isEmpty()) {
                request.header("api-key", apiKey);
            }
            String payload = body == null ? "" : MAPPER.writeValueAsString(body);
            request.method(method, payload.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(payload));
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("qdrant " + method + " " + path + " -> "
                        + response.statusCode() + ": " + truncate(response.body()));
            }
            String text = response.body();
            return text == null || text.isBlank() ? MAPPER.createObjectNode()
                    : MAPPER.readTree(text);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qdrant call interrupted", interrupted);
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException state) {
                throw state;
            }
            String detail = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            throw new IllegalStateException("qdrant call failed: " + detail, failure);
        }
    }

    private static Map<String, Object> sessionFilter(String sessionId) {
        return Map.of("must", List.of(Map.of(
                "key", "session_id",
                "match", Map.of("value", sessionId))));
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }

    private String collection(String model, int dim) {
        String safe = model == null ? "unknown" : model.replaceAll("[^A-Za-z0-9_-]", "_");
        String name = "ff_facts_" + safe + "_" + dim;
        return name.length() <= COLLECTION_NAME_LIMIT ? name : name.substring(0, COLLECTION_NAME_LIMIT);
    }

    private static String stripTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
