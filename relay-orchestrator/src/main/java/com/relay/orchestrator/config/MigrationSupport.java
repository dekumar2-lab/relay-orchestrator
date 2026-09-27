package com.relay.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared helpers for schema management. Stores extend this to get
 * idempotent column-adds and table-existence checks.
 *
 * The DB is currently a single SQLite file. When we split into
 * microservices, each store will point at its own DB but still use
 * these helpers locally.
 */
public abstract class MigrationSupport {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final JdbcTemplate jdbc;

    protected MigrationSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True if the given table exists in this DB. */
    protected boolean tableExists(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                Integer.class, name);
        return n != null && n > 0;
    }

    /** True if the given column exists on the given table. */
    protected boolean columnExists(String table, String column) {
        if (!tableExists(table))
            return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    /**
     * Add a column if it doesn't exist. Definition includes type and
     * default, e.g. "TEXT NOT NULL DEFAULT 'default'".
     */
    protected void addColumnIfMissing(String table, String column, String definition) {
        if (tableExists(table) && !columnExists(table, column)) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Added column {}.{}", table, column);
        }
    }
}