package com.forgeflow.common;

import jakarta.validation.constraints.NotBlank;

/**
 * A human's decision on a task parked in {@code WAITING_HUMAN}.
 * {@code decision} is RESUME (optionally with an answer appended to the prompt) or REJECT.
 */
public record ResolutionCommand(@NotBlank String decision, String answer) {
}
