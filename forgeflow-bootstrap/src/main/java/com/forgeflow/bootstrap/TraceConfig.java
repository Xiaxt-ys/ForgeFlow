package com.forgeflow.bootstrap;

import com.forgeflow.common.trace.TaskTracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TraceConfig {

    @Bean
    public TaskTracer taskTracer() {
        return new TaskTracer();
    }
}
