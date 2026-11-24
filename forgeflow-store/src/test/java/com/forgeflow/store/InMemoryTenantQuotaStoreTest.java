package com.forgeflow.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTenantQuotaStoreTest {

    @Test
    void limitsInflightPerTenantAndReleases() {
        TenantQuotaProperties properties = new TenantQuotaProperties();
        properties.setMaxInflight(2);
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore(properties);

        assertThat(store.tryAcquire("tenant-a")).isTrue();
        assertThat(store.tryAcquire("tenant-a")).isTrue();
        assertThat(store.tryAcquire("tenant-a")).isFalse();
        assertThat(store.tryAcquire("tenant-b")).isTrue();

        store.release("tenant-a");
        assertThat(store.tryAcquire("tenant-a")).isTrue();
    }

    @Test
    void zeroLimitDisablesTheGate() {
        TenantQuotaProperties properties = new TenantQuotaProperties();
        properties.setMaxInflight(0);
        InMemoryTenantQuotaStore store = new InMemoryTenantQuotaStore(properties);

        assertThat(store.tryAcquire("tenant-a")).isTrue();
        assertThat(store.tryAcquire("tenant-a")).isTrue();
    }
}
