package com.forgeflow.scheduler;

import com.forgeflow.common.TaskDispatcher;
import com.forgeflow.common.TaskMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "forgeflow.dispatch.mode", havingValue = "rabbit", matchIfMissing = true)
public class RabbitTaskDispatcher implements TaskDispatcher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitTaskDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void dispatch(TaskMessage message) {
        rabbitTemplate.convertAndSend(RabbitConfig.TASK_EXCHANGE, RabbitConfig.TASK_ROUTING, message);
    }
}
