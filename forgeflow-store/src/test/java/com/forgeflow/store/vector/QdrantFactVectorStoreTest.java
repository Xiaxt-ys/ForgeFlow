package com.forgeflow.store.vector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantFactVectorStoreTest {

    private static final Pattern COLLECTION = Pattern.compile("^/collections/([^/]+)$");
    private static final Pattern POINTS = Pattern.compile("^/collections/([^/]+)/points$");
    private static final Pattern SEARCH = Pattern.compile("^/collections/([^/]+)/points/search$");
    private static final Pattern COUNT = Pattern.compile("^/collections/([^/]+)/points/count$");
    private static final Pattern INDEX = Pattern.compile("^/collections/([^/]+)/index$");

    private HttpServer server;
    private final Map<String, Map<String, FakePoint>> collections = new ConcurrentHashMap<>();
    private final List<String> calls = new ArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record FakePoint(List<Float> vector, String sessionId, String factKey) {
    }

    private String serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(method + " " + path);
        Matcher collection = COLLECTION.matcher(path);
        Matcher points = POINTS.matcher(path);
        Matcher search = SEARCH.matcher(path);
        Matcher count = COUNT.matcher(path);
        Matcher index = INDEX.matcher(path);

        if (collection.matches()) {
            String name = collection.group(1);
            if ("GET".equals(method)) {
                respond(exchange, collections.containsKey(name) ? 200 : 404, "{}");
            } else {
                collections.putIfAbsent(name, new ConcurrentHashMap<>());
                respond(exchange, 200, "{\"result\":true}");
            }
            return;
        }
        if (points.matches()) {
            Map<String, FakePoint> store = collections.computeIfAbsent(points.group(1),
                    key -> new ConcurrentHashMap<>());
            for (Map<String, Object> point : points(body)) {
                store.put(String.valueOf(point.get("id")), new FakePoint(
                        floats(point.get("vector")),
                        String.valueOf(((Map<?, ?>) point.get("payload")).get("session_id")),
                        String.valueOf(((Map<?, ?>) point.get("payload")).get("fact_key"))));
            }
            respond(exchange, 200, "{\"result\":{\"status\":\"completed\"}}");
            return;
        }
        if (search.matches()) {
            Map<String, FakePoint> store = collections.getOrDefault(search.group(1), Map.of());
            List<Float> query = floats(node(body, "vector"));
            String session = sessionFilter(body);
            List<Map.Entry<String, FakePoint>> ranked = store.entrySet().stream()
                    .filter(entry -> session == null || session.equals(entry.getValue().sessionId()))
                    .sorted(Comparator.comparingDouble(
                            (Map.Entry<String, FakePoint> entry) -> cosine(query,
                                    entry.getValue().vector())).reversed())
                    .limit(limit(body))
                    .toList();
            StringBuilder results = new StringBuilder();
            for (Map.Entry<String, FakePoint> entry : ranked) {
                if (results.length() > 0) {
                    results.append(',');
                }
                results.append("{\"id\":\"").append(entry.getKey()).append("\"}");
            }
            respond(exchange, 200, "{\"result\":[" + results + "]}");
            return;
        }
        if (count.matches()) {
            Map<String, FakePoint> store = collections.getOrDefault(count.group(1), Map.of());
            String session = sessionFilter(body);
            long matching = store.values().stream()
                    .filter(point -> session == null || session.equals(point.sessionId()))
                    .count();
            respond(exchange, 200, "{\"result\":{\"count\":" + matching + "}}");
            return;
        }
        if (index.matches()) {
            respond(exchange, 200, "{\"result\":true}");
            return;
        }
        respond(exchange, 404, "{}");
    }

    @Test
    void createsCollectionIndexesSearchesAndCountsBySession() {
        QdrantFactVectorStore store = new QdrantFactVectorStore(url(), "", 2000);
        float[] alice = {1f, 0f};
        float[] other = {0f, 1f};

        store.index("mock-embedding", 2, "s1", "11111111-1111-1111-1111-111111111111",
                "user_name", alice);
        store.index("mock-embedding", 2, "s1", "22222222-2222-2222-2222-222222222222",
                "pet", other);
        store.index("mock-embedding", 2, "s2", "33333333-3333-3333-3333-333333333333",
                "user_name", alice);

        assertThat(calls).contains("PUT /collections/ff_facts_mock-embedding_2");
        assertThat(store.collectionName("mock-embedding", 2)).isEqualTo("ff_facts_mock-embedding_2");
        assertThat(store.count("mock-embedding", 2, "s1")).isEqualTo(2);
        assertThat(store.count("mock-embedding", 2, "s2")).isEqualTo(1);

        List<String> hits = store.search("mock-embedding", 2, "s1", new float[]{1f, 0f}, 5);
        assertThat(hits).containsExactly("11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222");

        // The collection is only created once, and the second model gets its own collection.
        long creates = calls.stream()
                .filter(call -> call.equals("PUT /collections/ff_facts_mock-embedding_2"))
                .count();
        assertThat(creates).isEqualTo(1);
        store.index("other-model", 4, "s1", "44444444-4444-4444-4444-444444444444",
                "k", new float[]{1f, 0f, 0f, 0f});
        assertThat(calls).contains("PUT /collections/ff_facts_other-model_4");
    }

    @Test
    void unreachableQdrantFailsLoudlySoTheCallerCanFallBack() {
        QdrantFactVectorStore store =
                new QdrantFactVectorStore("http://127.0.0.1:1", "", 500);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        store.search("m", 2, "s1", new float[]{1f, 0f}, 3))
                .isInstanceOf(IllegalStateException.class);
    }

    private String url() {
        try {
            return serve();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> points(String body) throws IOException {
        Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, Map.class);
        return (List<Map<String, Object>>) root.get("points");
    }

    @SuppressWarnings("unchecked")
    private static Object node(String body, String field) throws IOException {
        Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, Map.class);
        return root.get(field);
    }

    @SuppressWarnings("unchecked")
    private static String sessionFilter(String body) throws IOException {
        Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, Map.class);
        Map<String, Object> filter = (Map<String, Object>) root.get("filter");
        if (filter == null) {
            return null;
        }
        List<Map<String, Object>> must = (List<Map<String, Object>>) filter.get("must");
        Map<String, Object> match = (Map<String, Object>) must.get(0).get("match");
        return String.valueOf(match.get("value"));
    }

    private static int limit(String body) throws IOException {
        Object value = node(body, "limit");
        return value == null ? 10 : ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Float> floats(Object raw) {
        List<Float> values = new ArrayList<>();
        for (Object value : (List<Object>) raw) {
            values.add(((Number) value).floatValue());
        }
        return values;
    }

    private static double cosine(List<Float> a, List<Float> b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
