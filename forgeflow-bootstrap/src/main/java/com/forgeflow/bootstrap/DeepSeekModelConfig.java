package com.forgeflow.bootstrap;

import com.agentsflex.core.model.chat.BaseChatModel;
import com.agentsflex.core.model.chat.ChatContext;
import com.agentsflex.core.model.chat.ChatInterceptor;
import com.agentsflex.core.model.chat.ChatOptions;
import com.agentsflex.core.model.chat.StreamChain;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.SyncChain;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.model.chat.deepseek.DeepseekChatModel;
import com.agentsflex.model.chat.deepseek.DeepseekConfig;
import com.forgeflow.worker.model.ModelHealthStore;
import com.forgeflow.worker.model.RoutingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wires a DeepSeek chat model into the agents-flex AgentRunner and wraps it in
 * {@link RoutingChatModel} so first-packet timeout and the three-state breaker apply.
 *
 * <p>Enable with {@code forgeflow.llm.deepseek.enabled=true}; the API key is read from
 * the environment so it never lands in the repository.</p>
 */
@Configuration
@EnableConfigurationProperties(DeepSeekModelProperties.class)
@ConditionalOnProperty(prefix = "forgeflow.llm.deepseek", name = "enabled", havingValue = "true")
public class DeepSeekModelConfig {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelConfig.class);
    private static final Set<String> EFFORTS = Set.of("low", "high", "max");

    @Bean
    public DeepseekChatModel deepSeekChatModel(DeepSeekModelProperties properties) {
        String apiKey = resolveApiKey(properties);
        List<ChatInterceptor> interceptors = List.of(requestTuning(properties));
        return new DeepseekChatModel(deepSeekConfig(properties, properties.model(), apiKey), interceptors);
    }

    @Bean
    public RoutingChatModel routingChatModel(DeepSeekModelProperties properties) {
        String apiKey = resolveApiKey(properties);
        log.info("deepseek configured model={} fallback={} thinking={} effort={} endpoint={}{} timeout={}ms",
                properties.model(),
                properties.fallbackModel() == null || properties.fallbackModel().isBlank()
                        ? "(none)" : properties.fallbackModel(),
                properties.thinking(),
                properties.thinking() ? normalizeEffort(properties.reasoningEffort()) : "-",
                properties.baseUrl(), properties.requestPath(),
                properties.firstPacketTimeoutMs());
        List<RoutingChatModel.Candidate> candidates = new ArrayList<>();
        candidates.add(new RoutingChatModel.Candidate(properties.model(),
                new DeepseekChatModel(deepSeekConfig(properties, properties.model(), apiKey))));
        if (properties.fallbackModel() != null && !properties.fallbackModel().isBlank()) {
            candidates.add(new RoutingChatModel.Candidate(properties.fallbackModel(),
                    new DeepseekChatModel(deepSeekConfig(properties, properties.fallbackModel(), apiKey))));
        }
        ModelHealthStore health = new ModelHealthStore(
                properties.failureThreshold(), properties.openDurationMs());
        return new RoutingChatModel(candidates, health,
                Duration.ofMillis(properties.firstPacketTimeoutMs()));
    }

    private static String resolveApiKey(DeepSeekModelProperties properties) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "forgeflow.llm.deepseek.api-key is empty; export DEEPSEEK_API_KEY "
                            + "or pass --forgeflow.llm.deepseek.api-key=...");
        }
        return apiKey.trim();
    }

    static DeepseekConfig deepSeekConfig(DeepSeekModelProperties properties, String model, String apiKey) {
        DeepseekConfig config = new DeepseekConfig();
        config.setProvider("deepseek");
        config.setApiKey(apiKey);
        config.setEndpoint(properties.baseUrl());
        config.setRequestPath(properties.requestPath());
        config.setModel(model);
        config.setSupportThinking(true);
        config.setSupportTool(true);
        config.setThinkingEnabled(properties.thinking());
        // DeepSeek requires reasoning_content to be sent back on tool-call turns.
        config.setPreserveThinkingEnable(properties.thinking());
        config.setRetryEnabled(true);
        config.setRetryCount(2);
        return config;
    }

    static ChatInterceptor requestTuning(DeepSeekModelProperties properties) {
        return new ChatInterceptor() {
            @Override
            public AiMessageResponse intercept(BaseChatModel<?> chatModel, ChatContext context, SyncChain chain) {
                apply(context, properties);
                return chain.proceed(chatModel, context);
            }

            @Override
            public void interceptStream(BaseChatModel<?> chatModel, ChatContext context,
                                        StreamResponseListener listener, StreamChain chain) {
                apply(context, properties);
                chain.proceed(chatModel, context, listener);
            }
        };
    }

    private static void apply(ChatContext context, DeepSeekModelProperties properties) {
        ChatOptions options = context.getOptions() == null ? new ChatOptions() : context.getOptions();
        if (options.getMaxTokens() == null) {
            options.setMaxTokens(properties.maxTokens());
        }
        if (properties.thinking()) {
            options.addExtraBody("reasoning_effort", normalizeEffort(properties.reasoningEffort()));
        } else if (options.getTemperature() == null && properties.temperature() != null) {
            // Sampling parameters are ignored while thinking is enabled, so only send them here.
            options.setTemperature(properties.temperature().floatValue());
        }
        if (log.isDebugEnabled()) {
            log.debug("llm.request model={} thinking={} effort={} maxTokens={}",
                    context.getConfig() == null ? "?" : context.getConfig().getModel(),
                    properties.thinking(),
                    properties.thinking() ? normalizeEffort(properties.reasoningEffort()) : "-",
                    options.getMaxTokens());
        }
        context.setOptions(options);
    }

    private static String normalizeEffort(String effort) {
        String normalized = effort.trim().toLowerCase(Locale.ROOT);
        if (!EFFORTS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "forgeflow.llm.deepseek.reasoning-effort must be one of " + EFFORTS + ", got: " + effort);
        }
        return normalized;
    }
}
