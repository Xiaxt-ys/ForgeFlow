package com.forgeflow.worker.multiagent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputValidatorTest {

    private final OutputValidator validator = new OutputValidator(100, true);

    @Test
    void rejectsEmptyOutput() {
        assertThatThrownBy(() -> validator.validate("s1", "   "))
                .isInstanceOf(InvalidAgentOutputException.class)
                .hasMessageContaining("s1")
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsOversizedOutput() {
        assertThatThrownBy(() -> validator.validate(null, "x".repeat(101)))
                .isInstanceOf(InvalidAgentOutputException.class)
                .hasMessageContaining("limit is 100");
    }

    @Test
    void rejectsMalformedJsonThatClaimsToBeJson() {
        assertThatThrownBy(() -> validator.validate(null, "{\"a\": 1,"))
                .isInstanceOf(InvalidAgentOutputException.class)
                .hasMessageContaining("does not parse");
    }

    @Test
    void acceptsPlainTextAndValidJson() {
        assertThatCode(() -> validator.validate(null, "a normal answer")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(null, "{\"a\":1}")).doesNotThrowAnyException();
    }
}
