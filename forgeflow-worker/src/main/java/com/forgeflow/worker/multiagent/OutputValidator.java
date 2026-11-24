package com.forgeflow.worker.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Structural check on agent output before it becomes the task result.
 *
 * <p>"The model says it is done" is not evidence: the output must be non-empty, within a
 * size bound, and — when it looks like JSON — actually parse. Business-rule and
 * second-model review are intentionally out of scope here; they depend on the domain.</p>
 */
public final class OutputValidator {

    private final int maxChars;
    private final boolean validateJson;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutputValidator(int maxChars, boolean validateJson) {
        this.maxChars = Math.max(1, maxChars);
        this.validateJson = validateJson;
    }

    public void validate(String subtaskId, String output) {
        String where = subtaskId == null ? "output" : "subtask " + subtaskId;
        if (output == null || output.isBlank()) {
            throw new InvalidAgentOutputException(where + " is empty");
        }
        if (output.length() > maxChars) {
            throw new InvalidAgentOutputException(
                    where + " is " + output.length() + " chars, limit is " + maxChars);
        }
        String trimmed = output.trim();
        if (validateJson && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            try {
                mapper.readTree(trimmed);
            } catch (Exception malformed) {
                throw new InvalidAgentOutputException(
                        where + " looks like JSON but does not parse: " + malformed.getMessage());
            }
        }
    }
}
