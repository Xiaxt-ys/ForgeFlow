package com.forgeflow.bootstrap;

import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.client.OpenAIChatRequestSpecBuilder;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekModelConfigTest {

    private static DeepSeekModelProperties properties(boolean thinking, String effort) {
        return new DeepSeekModelProperties(true, "test-key", "https://api.deepseek.com",
                "/chat/completions", "deepseek-v4-flash", "deepseek-v4-pro", thinking, effort,
                0.3, 4096, 180_000L, 3, 30_000L);
    }

    private static String bodyFor(DeepSeekModelProperties properties) {
        DeepseekConfig config = DeepSeekModelConfig.deepSeekConfig(
                properties, "deepseek-v4-flash", "test-key");
        ChatContext context = new ChatContext();
        context.setPrompt(new SimplePrompt("hello"));
        context.setOptions(new ChatOptions());
        ChatInterceptor interceptor = DeepSeekModelConfig.requestTuning(properties);
        interceptor.intercept(null, context, (model, ctx) -> null);
        return new OpenAIChatRequestSpecBuilder()
                .buildRequestBody(context.getPrompt(), context.getOptions(), config);
    }

    @Test
    void thinkingRequestCarriesEffortAndDropsSamplingParams() {
        String body = bodyFor(properties(true, "high"));

        assertThat(body).contains("\"thinking\":{\"type\":\"enabled\"}");
        assertThat(body).contains("\"reasoning_effort\":\"high\"");
        assertThat(body).contains("\"max_tokens\":4096");
        // DeepSeek ignores temperature while thinking, so it must not be sent.
        assertThat(body).doesNotContain("temperature");
    }

    @Test
    void nonThinkingRequestUsesTemperature() {
        String body = bodyFor(properties(false, "high"));

        assertThat(body).contains("\"thinking\":{\"type\":\"disabled\"}");
        assertThat(body).contains("\"temperature\":0.3");
        assertThat(body).doesNotContain("reasoning_effort");
    }

    @Test
    void maxEffortIsAcceptedAndUnknownEffortIsRejected() {
        assertThat(bodyFor(properties(true, "max"))).contains("\"reasoning_effort\":\"max\"");
        assertThatThrownBy(() -> bodyFor(properties(true, "medium")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning-effort");
    }
}
