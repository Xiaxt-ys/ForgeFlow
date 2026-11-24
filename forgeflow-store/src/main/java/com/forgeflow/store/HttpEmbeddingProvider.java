package com.forgeflow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI-compatible embedding endpoint ({@code POST {base-url}/embeddings}).
 *
 * <p>Any failure — network, non-2xx, malformed payload — degrades to the local hash vectoriser
 * for {@code cooldown-ms}, because memory is an enhancement: an embedding outage must not fail a
 * task or an entry read. The returned {@link Embedding#model()} always names the model that
 * actually produced the vectors, so mixed spaces are impossible.</p>
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final String url;
    private final String apiKey;
    private final Duration timeout;
    private final long cooldownMs;
    private final EmbeddingProvider fallback;
    private final HttpClient client;
    private final AtomicLong degradedUntil = new AtomicLong();

    public HttpEmbeddingProvider(String model, String baseUrl, String path, String apiKey,
                                 long timeoutMs, long cooldownMs) {
        this.model = model;
        this.url = join(baseUrl, path);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofMillis(Math.max(200, timeoutMs));
        this.cooldownMs = Math.max(1_000, cooldownMs);
        this.fallback = new HashEmbeddingProvider();
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public String model() {
        return model;
    }

    public boolean degraded() {
        return System.currentTimeMillis() < degradedUntil.get();
    }

    @Override
    public Embedding embed(List<String> texts) {
        if (texts.isEmpty()) {
            return new Embedding(model, List.of());
        }
        if (degraded()) {
            return fallback.embed(texts);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", texts);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (!apiKey.isEmpty()) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": "
                        + truncate(response.body()));
            }
            List<float[]> vectors = parse(MAPPER.readTree(response.body()), texts.size());
            return new Embedding(model, vectors);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return degrade(texts, "interrupted");
        } catch (Exception failure) {
            return degrade(texts, failure.getMessage());
        }
    }

    private Embedding degrade(List<String> texts, String reason) {
        degradedUntil.set(System.currentTimeMillis() + cooldownMs);
        log.warn("memory.embedding degraded for {}ms model={} reason={} — using {}",
                cooldownMs, model, reason, HashEmbeddingProvider.MODEL);
        return fallback.embed(texts);
    }

    private static List<float[]> parse(JsonNode root, int expected) {
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != expected) {
            throw new IllegalStateException("unexpected embedding response shape");
        }
        List<float[]> ordered = new ArrayList<>(java.util.Collections.nCopies(expected, null));
        int nextFree = 0;
        for (JsonNode item : data) {
            JsonNode values = item.path("embedding");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("empty embedding in response");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            // OpenAI-compatible providers return an index; honour it when present.
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < expected && ordered.get(index) == null) {
                ordered.set(index, vector);
            } else {
                while (nextFree < expected && ordered.get(nextFree) != null) {
                    nextFree++;
                }
                if (nextFree >= expected) {
                    throw new IllegalStateException("duplicate embedding index");
                }
                ordered.set(nextFree, vector);
            }
        }
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalStateException("missing embedding in response");
        }
        return ordered;
    }

    private static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = path == null || path.isBlank() ? "/embeddings" : path.trim();
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
