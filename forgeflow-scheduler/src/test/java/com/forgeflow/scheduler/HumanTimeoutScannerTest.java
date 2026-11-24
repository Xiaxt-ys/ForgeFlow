package com.forgeflow.scheduler;

import com.forgeflow.common.TaskEvent;
import com.forgeflow.common.TaskRecord;
import com.forgeflow.common.TaskStatus;
import com.forgeflow.store.TaskEventBus;
import com.forgeflow.store.TaskRepository;
import com.forgeflow.store.TenantQuotaStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HumanTimeoutScannerTest {

    private final TaskRepository repository = mock(TaskRepository.class);
    private final TenantQuotaStore quota = mock(TenantQuotaStore.class);
    private final TaskEventBus events = mock(TaskEventBus.class);

    private static TaskRecord waitingTask() {
        Instant now = Instant.now();
        return new TaskRecord("t1", "tenant-1", TaskStatus.WAITING_HUMAN, 4L, null, null,
                "prompt", null, "WAITING_HUMAN", "needs human", now, now);
    }

    @Test
    void rejectsWaitingTaskAndReleasesQuota() {
        when(repository.findStale(anyList(), any(), anyInt()))
                .thenReturn(List.of(waitingTask()));
        when(repository.markRejectedByHuman(eq("t1"), eq(4L), anyString())).thenReturn(true);

        new HumanTimeoutScanner(repository, quota, events, 30).scan();

        verify(repository).markRejectedByHuman(eq("t1"), eq(4L), contains("human timeout"));
        verify(quota).release("tenant-1");
        verify(events).publish(argThat(event -> event.status() == TaskStatus.FAILED
                && "rejected: human timeout".equals(event.detail())));
    }

    @Test
    void keepsQuotaWhenAnotherProcessAlreadyResolvedTheTask() {
        when(repository.findStale(anyList(), any(), anyInt()))
                .thenReturn(List.of(waitingTask()));
        when(repository.markRejectedByHuman(eq("t1"), eq(4L), anyString())).thenReturn(false);

        new HumanTimeoutScanner(repository, quota, events, 30).scan();

        verify(quota, never()).release(anyString());
        verify(events, never()).publish(any(TaskEvent.class));
    }

    @Test
    void doesNothingWhenNoTaskIsWaiting() {
        when(repository.findStale(anyList(), any(), anyInt())).thenReturn(List.of());

        new HumanTimeoutScanner(repository, quota, events, 30).scan();

        verifyNoInteractions(quota, events);
    }
}
