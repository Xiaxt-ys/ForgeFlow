package com.forgeflow.worker;

import com.forgeflow.common.AgentExecutor;
import com.forgeflow.common.AgentRequest;
import com.forgeflow.common.AgentResult;
import com.forgeflow.common.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forgeflow.agent.executor", havingValue = "echo", matchIfMissing = true)
public class EchoAgentExecutor implements AgentExecutor {

    private final long delayMs;

    public EchoAgentExecutor(@Value("${forgeflow.agent.echo.delay-ms:100}") long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public AgentResult execute(AgentRequest request, ExecutionContext context) throws Exception {
        if (context.cancelled()) {
            throw new InterruptedException("cancelled before execution");
        }
        Thread.sleep(delayMs);
        if (context.cancelled()) {
            throw new InterruptedException("cancelled during execution");
        }
        return new AgentResult("echo: " + request.prompt());
    }
}
