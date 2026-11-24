package com.forgeflow.worker.loop;

import com.agentsflex.core.model.chat.tool.ToolChain;
import com.agentsflex.core.model.chat.tool.ToolContext;
import com.agentsflex.core.model.chat.tool.ToolInterceptor;
import com.forgeflow.worker.tool.TaskContextHolder;

/**
 * Feeds every tool call to the loop guard before the tool runs. Signal 1 (same tool and
 * arguments repeated) and signal 3 (step cap) are decided here.
 */
public final class LoopGuardToolInterceptor implements ToolInterceptor {

    private final LoopGuard guard;

    public LoopGuardToolInterceptor(LoopGuard guard) {
        this.guard = guard;
    }

    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        guard.onToolCall(TaskContextHolder.get(), context.getTool().getName(), context.getArgsMap());
        return chain.proceed(context);
    }
}
