package com.forgeflow.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A task submission. {@code sessionId} is optional; when present the entry agent loads the
 * session's summary/facts and the exit agent writes new memory back.
 */
public record SubmitTaskCommand(
        @NotBlank String tenantId,
        @NotBlank String prompt,
        @Size(max = 64) String sessionId
) {
}
