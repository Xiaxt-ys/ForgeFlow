package com.forgeflow.worker;

import com.forgeflow.worker.tool.ApprovalToken;
import com.forgeflow.worker.tool.ApprovalTokenService;
import com.forgeflow.worker.tool.ToolCall;
import com.forgeflow.worker.tool.ToolGate;
import com.forgeflow.worker.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGateTest {

    private final ApprovalTokenService tokens = new ApprovalTokenService("test-secret");
    private final ToolGate gate = new ToolGate(Map.of(
            "search", ToolSpec.read("search"),
            "refund", ToolSpec.write("refund")), tokens);

    @Test
    void readToolNeedsNoApproval() {
        ToolCall call = new ToolCall("t1", "s1", "search", ApprovalTokenService.hashArgs("{}"));
        assertThat(gate.check(call, null).allowed()).isTrue();
    }

    @Test
    void writeToolRequiresApproval() {
        ToolCall call = new ToolCall("t1", "s1", "refund", ApprovalTokenService.hashArgs("{\"order\":1}"));
        assertThat(gate.check(call, null).allowed()).isFalse();

        ApprovalToken token = tokens.issue(call, Instant.now().plusSeconds(60));
        assertThat(gate.check(call, token).allowed()).isTrue();
    }

    @Test
    void changingArgsInvalidatesApproval() {
        ToolCall approved = new ToolCall("t1", "s1", "refund", ApprovalTokenService.hashArgs("{\"amount\":1}"));
        ApprovalToken token = tokens.issue(approved, Instant.now().plusSeconds(60));
        ToolCall tampered = new ToolCall("t1", "s1", "refund", ApprovalTokenService.hashArgs("{\"amount\":999}"));

        assertThat(gate.check(tampered, token).allowed()).isFalse();
    }

    @Test
    void expiredApprovalIsRejected() {
        ToolCall call = new ToolCall("t1", "s1", "refund", ApprovalTokenService.hashArgs("{}"));
        ApprovalToken token = tokens.issue(call, Instant.now().minusSeconds(1));
        assertThat(gate.check(call, token).allowed()).isFalse();
    }

    @Test
    void unknownToolIsRejected() {
        ToolCall call = new ToolCall("t1", "s1", "delete-everything", ApprovalTokenService.hashArgs("{}"));
        assertThat(gate.check(call, null).allowed()).isFalse();
    }
}
