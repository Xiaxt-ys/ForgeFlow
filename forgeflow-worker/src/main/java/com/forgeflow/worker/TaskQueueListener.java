package com.forgeflow.worker;

import com.forgeflow.common.TaskMessage;
import com.forgeflow.scheduler.RabbitConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnExpression("'${forgeflow.dispatch.mode:rabbit}' == 'rabbit' "
        + "and '${forgeflow.worker.enabled:true}' == 'true'")
public class TaskQueueListener {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueListener.class);

    private final TaskWorker worker;

    public TaskQueueListener(TaskWorker worker) {
        this.worker = worker;
    }

    @RabbitListener(queues = RabbitConfig.TASK_QUEUE)
    public void onMessage(TaskMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            worker.handle(message);
            channel.basicAck(deliveryTag, false);
        } catch (Exception fatal) {
            log.error("worker failed before committing task={}, send to retry", message.taskId(), fatal);
            // requeue=false: the queue's DLX moves it to the retry queue, then back to the task queue.
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
