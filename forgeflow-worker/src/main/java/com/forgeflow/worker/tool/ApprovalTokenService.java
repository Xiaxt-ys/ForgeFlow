package com.forgeflow.worker.tool;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

public class ApprovalTokenService {

    private final byte[] secret;

    public ApprovalTokenService(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public ApprovalToken issue(ToolCall call, Instant expiresAt) {
        String payload = payload(call, expiresAt);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(encoded));
        return new ApprovalToken(encoded + "." + signature);
    }

    public boolean verify(ApprovalToken token, ToolCall call) {
        if (token == null || token.value() == null) {
            return false;
        }
        String[] parts = token.value().split("\\.");
        if (parts.length != 2) {
            return false;
        }
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(parts[0]));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5) {
            return false;
        }
        long expiresAt = Long.parseLong(fields[4]);
        if (Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }
        return fields[0].equals(call.taskId())
                && fields[1].equals(call.stepId())
                && fields[2].equals(call.toolName())
                && fields[3].equals(call.argsHash());
    }

    public static String hashArgs(String canonicalArgs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalArgs.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash tool args", e);
        }
    }

    private String payload(ToolCall call, Instant expiresAt) {
        return call.taskId() + "|" + call.stepId() + "|" + call.toolName() + "|"
                + call.argsHash() + "|" + expiresAt.getEpochSecond();
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign approval token", e);
        }
    }
}
