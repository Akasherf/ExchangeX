package com.exchangex.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;

/** Real production repositories/coordinator are component-scanned by ExchangeApiApplication. */
@TestConfiguration
@Profile("postgres-integration")
public class PostgreSQLIntegrationTestConfig {
}
