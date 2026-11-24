package com.forgeflow.bootstrap;

import com.forgeflow.worker.tool.ApprovalTokenService;
import com.forgeflow.worker.tool.ApprovalTokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApprovalConfig {

    @Bean
    public ApprovalTokenService approvalTokenService(
            @Value("${forgeflow.approval.secret:forgeflow-dev-secret-change-me}") String secret) {
        return new ApprovalTokenService(secret);
    }

    @Bean
    public ApprovalTokenStore approvalTokenStore(ApprovalTokenService service) {
        return new ApprovalTokenStore(service);
    }
}
