package com.forgeflow.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.forgeflow")
@EnableScheduling
public class ForgeFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(ForgeFlowApplication.class, args);
    }
}
