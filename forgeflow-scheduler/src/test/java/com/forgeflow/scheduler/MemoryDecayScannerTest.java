package com.forgeflow.scheduler;

import com.forgeflow.store.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDecayScannerTest {

    private final SessionRepository repository = mock(SessionRepository.class);

    @Test
    void archivesStaleFactsAndCapsEachSession() {
        when(repository.archiveStale(any(Instant.class), eq(2), anyDouble())).thenReturn(3);
        when(repository.allSessionIds(1000)).thenReturn(List.of("s1", "s2"));
        when(repository.archiveOverflow("s1", 200)).thenReturn(1);
        when(repository.archiveOverflow("s2", 200)).thenReturn(0);

        MemoryDecayScanner scanner = new MemoryDecayScanner(repository, 7, 2, 0.8, 200);
        scanner.scan();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).archiveStale(cutoff.capture(), eq(2), eq(0.8));
        assertThat(Duration.between(cutoff.getValue(), Instant.now()).toDays()).isBetween(6L, 7L);
        verify(repository).archiveOverflow("s1", 200);
        verify(repository).archiveOverflow("s2", 200);
    }

    @Test
    void noSessionsStillRunsTheGlobalSweep() {
        when(repository.archiveStale(any(Instant.class), eq(2), anyDouble())).thenReturn(0);
        when(repository.allSessionIds(1000)).thenReturn(List.of());

        new MemoryDecayScanner(repository, 7, 2, 0.8, 200).scan();

        verify(repository).archiveStale(any(Instant.class), eq(2), anyDouble());
    }
}
