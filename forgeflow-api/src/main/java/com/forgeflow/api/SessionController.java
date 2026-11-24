package com.forgeflow.api;

import com.forgeflow.common.SessionNotFoundException;
import com.forgeflow.store.FactHistory;
import com.forgeflow.store.SessionMemoryStore;
import com.forgeflow.store.SessionRepository;
import com.forgeflow.store.SessionSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read and curate session memory. Facts can be deleted so a poisoned memory is recoverable
 * without touching the conversation tasks.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionMemoryStore memory;
    private final SessionRepository repository;

    public SessionController(SessionMemoryStore memory, SessionRepository repository) {
        this.memory = memory;
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public SessionSnapshot get(@PathVariable("id") String sessionId) {
        return memory.snapshot(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    @DeleteMapping("/{id}/facts/{key}")
    public ResponseEntity<Void> deleteFact(@PathVariable("id") String sessionId,
                                           @PathVariable("key") String key) {
        memory.snapshot(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        repository.deleteFact(sessionId, key);
        return ResponseEntity.noContent().build();
    }

    /** Every superseded or conflicting value of this fact, newest first. */
    @GetMapping("/{id}/facts/{key}/history")
    public List<FactHistory> history(@PathVariable("id") String sessionId,
                                     @PathVariable("key") String key,
                                     @RequestParam(name = "limit", defaultValue = "20") int limit) {
        memory.snapshot(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
        return memory.history(sessionId, key, Math.max(1, Math.min(limit, 100)));
    }
}
