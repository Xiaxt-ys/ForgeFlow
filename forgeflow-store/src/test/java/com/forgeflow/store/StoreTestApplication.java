package com.forgeflow.store;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal boot configuration so slice tests in this module have a context root.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class StoreTestApplication {
}
