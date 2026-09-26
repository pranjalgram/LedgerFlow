package com.ledgerflow;

import org.flywaydb.core.Flyway;

/** Separate release job: no web server, schedulers, Kafka or runtime credentials. */
public final class MigrationApplication {
    private MigrationApplication() { }
    public static void main(String[] args) {
        Flyway.configure().dataSource(required("DB_URL"), required("DB_MIGRATION_USERNAME"), required("DB_MIGRATION_PASSWORD"))
                .locations("classpath:db/migration").load().migrate();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required configuration: " + name);
        return value;
    }
}
