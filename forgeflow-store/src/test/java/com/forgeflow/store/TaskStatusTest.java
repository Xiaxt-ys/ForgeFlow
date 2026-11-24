package com.forgeflow.store;

import com.forgeflow.common.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WAITING_APPROVAL branch is supported by the state machine but has no producer yet:
 * a turn is never parked there because approval currently denies instead of suspending.
 * This test pins the transitions so wiring the approval resume later is a smaller change.
 */
class TaskStatusTest {

    @Test
    void waitingApprovalCanBeEnteredAndResumed() {
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.WAITING_APPROVAL)).isTrue();
        assertThat(TaskStatus.WAITING_APPROVAL.canTransitionTo(TaskStatus.RUNNING)).isTrue();
        assertThat(TaskStatus.WAITING_APPROVAL.canTransitionTo(TaskStatus.FAILED)).isTrue();
        assertThat(TaskStatus.WAITING_APPROVAL.canTransitionTo(TaskStatus.CANCELLING)).isTrue();
    }

    @Test
    void waitingHumanCanOnlyResumeOrFail() {
        assertThat(TaskStatus.WAITING_HUMAN.canTransitionTo(TaskStatus.QUEUED)).isTrue();
        assertThat(TaskStatus.WAITING_HUMAN.canTransitionTo(TaskStatus.FAILED)).isTrue();
        assertThat(TaskStatus.WAITING_HUMAN.canTransitionTo(TaskStatus.CANCELLED)).isTrue();
        assertThat(TaskStatus.WAITING_HUMAN.canTransitionTo(TaskStatus.RUNNING)).isFalse();
    }

    @Test
    void terminalStatesAreFinal() {
        assertThat(TaskStatus.SUCCEEDED.canTransitionTo(TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStatus.FAILED.canTransitionTo(TaskStatus.QUEUED)).isFalse();
        assertThat(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.QUEUED)).isFalse();
    }
}
