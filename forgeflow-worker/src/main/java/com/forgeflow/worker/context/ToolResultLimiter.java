package com.forgeflow.worker.context;

import com.agentsflex.core.model.chat.tool.ToolChain;
import com.agentsflex.core.model.chat.tool.ToolContext;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;

/**
 * Tool result limiter: keeps large tool outputs out of the model context.
 * The placeholder keeps the original size and tells the model it can re-call the tool.
 */
public class ToolResultLimiter implements ToolInterceptor {

    public static final String EVICTED_PREFIX = "[tool result omitted, original ";
    public static final String EVICTED_SUFFIX = " chars, re-call the tool if needed]";

    private final int maxChars;

    public ToolResultLimiter(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        Object result = chain.proceed(context);
        if (result instanceof String text && text.length() > maxChars) {
            return EVICTED_PREFIX + text.length() + EVICTED_SUFFIX;
        }
        return result;
    }
}
