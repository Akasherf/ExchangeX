package com.exchangex.api.guard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

class WriterLeaseIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named="EXCHANGEX_TEST_DB_URL", matches=".+")
    void secondWriterCannotStartAndNewGenerationIsFenced() {
        var ds = new DriverManagerDataSource(
                System.getenv("EXCHANGEX_TEST_DB_URL"),
                System.getenv("EXCHANGEX_TEST_DB_USERNAME"),
                System.getenv("EXCHANGEX_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        Environment profile = new MockEnvironment().withProperty("spring.profiles.active", "postgres-integration");
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var first = new WriterLease(ds, jdbc, true, profile);
        var second = new WriterLease(ds, jdbc, true, profile);
        try {
            first.start();
            tx.executeWithoutResult(status -> first.assertFencedTransaction());
            assertThrows(IllegalStateException.class, second::start);
            tx.executeWithoutResult(status -> first.assertFencedTransaction());
            first.close();
            second.start();
            tx.executeWithoutResult(status -> second.assertFencedTransaction());
            assertThrows(IllegalStateException.class,
                    () -> tx.executeWithoutResult(status -> first.assertFencedTransaction()));
        } finally {
            first.close();
            second.close();
        }
    }
}
