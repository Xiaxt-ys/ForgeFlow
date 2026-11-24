package com.forgeflow.store;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpEmbeddingProviderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void parsesOpenAiCompatibleResponseAndHonoursIndex() throws IOException {
        String base = serve(200, """
                {"data":[
                  {"index":1,"embedding":[0.3,0.4]},
                  {"index":0,"embedding":[0.1,0.2]}]}
                """);
        HttpEmbeddingProvider provider =
                new HttpEmbeddingProvider("text-embedding-3-small", base, "/embeddings", "k",
                        2000, 60_000);

        Embedding embedding = provider.embed(List.of("a", "b"));

        assertThat(embedding.model()).isEqualTo("text-embedding-3-small");
        assertThat(embedding.vectors()).hasSize(2);
        assertThat(embedding.vectors().get(0)).containsExactly(0.1f, 0.2f);
        assertThat(embedding.vectors().get(1)).containsExactly(0.3f, 0.4f);
        assertThat(provider.degraded()).isFalse();
    }

    @Test
    void failureDegradesToTheHashVectoriser() throws IOException {
        String base = serve(500, "{\"error\":\"boom\"}");
        HttpEmbeddingProvider provider =
                new HttpEmbeddingProvider("text-embedding-3-small", base, "/embeddings", "",
                        2000, 60_000);

        Embedding embedding = provider.embed(List.of("Alice works on ForgeFlow"));

        assertThat(provider.degraded()).isTrue();
        assertThat(embedding.model()).isEqualTo(HashEmbeddingProvider.MODEL);
        assertThat(embedding.first()).hasSize(TextVectorizer.DIM);
    }

    @Test
    void malformedPayloadAlsoDegrades() throws IOException {
        String base = serve(200, "{\"data\":[]}");
        HttpEmbeddingProvider provider =
                new HttpEmbeddingProvider("m", base, "/embeddings", "", 2000, 60_000);

        Embedding embedding = provider.embed(List.of("a", "b"));

        assertThat(embedding.model()).isEqualTo(HashEmbeddingProvider.MODEL);
        assertThat(embedding.vectors()).hasSize(2);
    }

    @Test
    void degradedWindowSkipsFurtherHttpCalls() throws IOException {
        String base = serve(500, "{}");
        HttpEmbeddingProvider provider =
                new HttpEmbeddingProvider("m", base, "/embeddings", "", 1000, 60_000);
        provider.embed(List.of("first"));

        // The server can go away; the cooldown must answer locally without touching it.
        server.stop(0);
        server = null;
        Embedding second = provider.embed(List.of("second", "third"));

        assertThat(second.model()).isEqualTo(HashEmbeddingProvider.MODEL);
        assertThat(second.vectors()).hasSize(2);
    }
}
