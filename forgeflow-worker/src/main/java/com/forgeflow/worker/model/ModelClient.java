package com.forgeflow.worker.model;

import java.util.concurrent.CompletableFuture;

public interface ModelClient {
    String id();

    CompletableFuture<String> complete(String prompt);
}
