package com.JobHunt.appJob.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * Idempotent, additive SQLite migrations for columns introduced after the initial schema
 * (SQLite's {@code CREATE TABLE IF NOT EXISTS} cannot add columns to an existing table).
 *
 * <p>Each {@code ALTER TABLE ... ADD COLUMN} is attempted and the "duplicate column" error is
 * swallowed, so this is safe to run on every startup against both fresh and existing databases.</p>
 */
@Component
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private static final List<String> MIGRATIONS = List.of(
            "ALTER TABLE jobs ADD COLUMN stack TEXT",
            "ALTER TABLE jobs ADD COLUMN profile TEXT",
            "ALTER TABLE jobs ADD COLUMN score INTEGER",
            "ALTER TABLE jobs ADD COLUMN applied_at TEXT",
            // v1.3 FR-39a: single active profile that gates which jobs get emailed.
            "ALTER TABLE profiles ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0"
    );

    private final JdbcClient jdbc;

    public SchemaMigrator(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @EventListener(ApplicationStartedEvent.class)
    public void migrate() {
        for (String sql : MIGRATIONS) {
            try {
                jdbc.sql(sql).update();
                log.info("Applied migration: {}", sql);
            } catch (Exception e) {
                // Expected when the column already exists ("duplicate column name").
                log.debug("Migration skipped (already applied): {}", sql);
            }
        }
        rebuildProfilesForAccounts();
    }

    /**
     * User accounts: profiles moved from a globally unique {@code name} to per-user ownership
     * ({@code user_id}, unique per user). SQLite cannot drop a UNIQUE constraint in place, so a
     * pre-accounts table is copied into the new shape once. Existing rows keep their ids and get
     * {@code user_id = NULL}; {@link com.JobHunt.appJob.auth.AuthService} hands them to the owner
     * account when it signs up.
     */
    void rebuildProfilesForAccounts() {
        Long hasUserId = jdbc.sql("SELECT COUNT(*) FROM pragma_table_info('profiles') WHERE name = 'user_id'")
                .query(Long.class).single();
        if (hasUserId != null && hasUserId > 0) {
            return;
        }
        log.info("Migrating profiles table to per-user ownership");
        jdbc.sql("""
                CREATE TABLE profiles_v2 (
                  id             INTEGER PRIMARY KEY AUTOINCREMENT,
                  user_id        INTEGER REFERENCES users(id) ON DELETE CASCADE,
                  name           TEXT    NOT NULL,
                  include_title  TEXT,
                  stack          TEXT,
                  exclude_title  TEXT,
                  companies      TEXT,
                  exp_min        INTEGER,
                  exp_max        INTEGER,
                  enabled        INTEGER NOT NULL DEFAULT 1,
                  is_active      INTEGER NOT NULL DEFAULT 0,
                  created_at     TEXT    NOT NULL,
                  updated_at     TEXT,
                  UNIQUE (user_id, name)
                )""").update();
        jdbc.sql("""
                INSERT INTO profiles_v2 (id, user_id, name, include_title, stack, exclude_title, companies,
                                         exp_min, exp_max, enabled, is_active, created_at, updated_at)
                SELECT id, NULL, name, include_title, stack, exclude_title, companies,
                       exp_min, exp_max, enabled, is_active, created_at, updated_at
                FROM profiles""").update();
        jdbc.sql("DROP TABLE profiles").update();
        jdbc.sql("ALTER TABLE profiles_v2 RENAME TO profiles").update();
    }
}

