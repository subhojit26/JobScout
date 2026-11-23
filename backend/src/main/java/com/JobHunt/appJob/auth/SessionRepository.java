package com.JobHunt.appJob.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;

/** Login sessions. Stores only the SHA-256 hash of each bearer token. */
@Repository
class SessionRepository {

    private final JdbcClient jdbc;

    SessionRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    void insert(String tokenHash, long userId, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO sessions (token_hash, user_id, created_at, expires_at)
                VALUES (:h, :u, :now, :exp)
                """)
                .param("h", tokenHash).param("u", userId)
                .param("now", Instant.now().toString()).param("exp", expiresAt.toString())
                .update();
    }

    /** The user id for a live (unexpired) session. */
    Optional<Long> findUserId(String tokenHash) {
        return jdbc.sql("SELECT user_id FROM sessions WHERE token_hash = :h AND expires_at > :now")
                .param("h", tokenHash).param("now", Instant.now().toString())
                .query(Long.class).optional();
    }

    void delete(String tokenHash) {
        jdbc.sql("DELETE FROM sessions WHERE token_hash = :h").param("h", tokenHash).update();
    }

    void deleteAllForUser(long userId) {
        jdbc.sql("DELETE FROM sessions WHERE user_id = :u").param("u", userId).update();
    }

    void deleteExpired() {
        jdbc.sql("DELETE FROM sessions WHERE expires_at <= :now")
                .param("now", Instant.now().toString()).update();
    }
}
