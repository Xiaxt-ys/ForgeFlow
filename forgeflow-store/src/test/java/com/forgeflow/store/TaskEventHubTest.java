package com.forgeflow.store;

import com.forgeflow.common.TaskEvent;
import com.forgeflow.common.TaskStatus;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventHubTest {

    @Test
    void subscribersReceiveLifecycleEventsInOrder() {
        TaskEventHub hub = new TaskEventHub();
        List<TaskEvent> received = new CopyOnWriteArrayList<>();

        Disposable subscription = hub.stream("t1").subscribe(received::add);
        try {
            hub.publish(TaskEvent.status("t1", TaskStatus.QUEUED, "submitted"));
            hub.publish(TaskEvent.status("t1", TaskStatus.RUNNING, "worker"));
            hub.publish(TaskEvent.subtask("t1", "s1 RUNNING"));

            assertThat(received).extracting(TaskEvent::status)
                    .containsExactly(TaskStatus.QUEUED, TaskStatus.RUNNING, null);
            assertThat(received.get(2).type()).isEqualTo("subtask");
        } finally {
            subscription.dispose();
        }
    }

    @Test
    void eventsAreScopedPerTask() {
        TaskEventHub hub = new TaskEventHub();
        List<TaskEvent> other = new CopyOnWriteArrayList<>();

        Disposable subscription = hub.stream("other").subscribe(other::add);
        try {
            hub.publish(TaskEvent.status("t1", TaskStatus.QUEUED, "submitted"));
            assertThat(other).isEmpty();
        } finally {
            subscription.dispose();
        }
    }
}
