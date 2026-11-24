package com.forgeflow.worker;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolChain;
import com.agentsflex.core.model.chat.tool.ToolContext;
import com.agentsflex.core.message.ToolCall;
import com.forgeflow.worker.context.ToolResultLimiter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultLimiterTest {

    private final ToolResultLimiter limiter = new ToolResultLimiter(100);
    private final Tool tool = new Tool() {
        @Override
        public String getName() {
            return "search";
        }

        @Override
        public String getDescription() {
            return "search";
        }

        @Override
        public Parameter[] getParameters() {
            return new Parameter[0];
        }

        @Override
        public Object invoke(Map<String, Object> argsMap) {
            return null;
        }
    };

    @Test
    void largeResultIsReplacedWithRecallablePlaceholder() throws Exception {
        ToolContext context = new ToolContext(tool, new ToolCall("1", "search", "{}"));
        ToolChain chain = ctx -> "x".repeat(1_000);

        Object result = limiter.intercept(context, chain);

        assertThat(result.toString()).startsWith(ToolResultLimiter.EVICTED_PREFIX);
        assertThat(result.toString()).contains("1000");
    }

    @Test
    void smallResultIsUntouched() throws Exception {
        ToolContext context = new ToolContext(tool, new ToolCall("1", "search", "{}"));
        ToolChain chain = ctx -> "short";

        assertThat(limiter.intercept(context, chain)).isEqualTo("short");
    }
}
