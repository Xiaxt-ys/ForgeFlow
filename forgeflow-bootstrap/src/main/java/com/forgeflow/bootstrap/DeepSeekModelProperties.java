package com.forgeflow.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek (OpenAI-compatible) model settings.
 *
 * <p>Thinking mode is on by default on the server side; {@code reasoning-effort}
 * is the DeepSeek equivalent of an "effort" dial and accepts {@code low},
 * {@code high} or {@code max}. Temperature and the other sampling parameters are
 * ignored by DeepSeek while thinking is enabled, so they are only sent when
 * {@code thinking=false}.</p>
 */
@ConfigurationProperties(prefix = "forgeflow.llm.deepseek")
public record DeepSeekModelProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String requestPath,
        String model,
        String fallbackModel,
        boolean thinking,
        String reasoningEffort,
        Double temperature,
        Integer maxTokens,
        long firstPacketTimeoutMs,
        int failureThreshold,
        long openDurationMs) {

    public DeepSeekModelProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        if (requestPath == null || requestPath.isBlank()) {
            requestPath = "/chat/completions";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-v4-flash";
        }
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            reasoningEffort = "low";
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 4096;
        }
        if (firstPacketTimeoutMs <= 0) {
            firstPacketTimeoutMs = 180_000L;
        }
        if (failureThreshold <= 0) {
            failureThreshold = 3;
        }
        if (openDurationMs <= 0) {
            openDurationMs = 30_000L;
        }
    }
}
