package com.forgeflow.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the memory embedding backend.
 *
 * <p>{@code provider=hash} (default) is offline and dependency-free; {@code provider=http} points
 * at any OpenAI-compatible embeddings endpoint and degrades to hash on failure.</p>
 */
@Configuration
public class MemoryEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingConfig.class);

    @Bean
    public EmbeddingProvider embeddingProvider(
            @Value("${forgeflow.memory.embedding.provider:hash}") String provider,
            @Value("${forgeflow.memory.embedding.model:BAAI/bge-m3}") String model,
            @Value("${forgeflow.memory.embedding.base-url:https://api.siliconflow.cn/v1}")
            String baseUrl,
            @Value("${forgeflow.memory.embedding.path:/embeddings}") String path,
            @Value("${forgeflow.memory.embedding.api-key:}") String apiKey,
            @Value("${forgeflow.memory.embedding.timeout-ms:3000}") long timeoutMs,
            @Value("${forgeflow.memory.embedding.cooldown-ms:60000}") long cooldownMs) {
        if ("http".equalsIgnoreCase(provider)) {
            log.info("memory.embedding provider=http model={} url={}{}", model, baseUrl, path);
            return new HttpEmbeddingProvider(model, baseUrl, path, apiKey, timeoutMs, cooldownMs);
        }
        log.info("memory.embedding provider=hash model={}", HashEmbeddingProvider.MODEL);
        return new HashEmbeddingProvider();
    }
}
