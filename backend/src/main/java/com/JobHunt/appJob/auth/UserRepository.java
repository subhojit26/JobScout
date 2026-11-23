package com.JobHunt.appJob.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC access to the {@code users} table. Emails compare case-insensitively. */
@Repository
public class UserRepository {

    /** A user row together with its stored password hash (for login checks only). */
    record Credentials(User user, String passwordHash) {}

    private final JdbcClient jdbc;

    public UserRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<User> findById(long id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id)
                .query(UserRepository::mapUser).optional();
    }

    Optional<Credentials> findCredentials(String email) {
        return jdbc.sql("SELECT * FROM users WHERE email = :email COLLATE NOCASE")
                .param("email", email)
                .query((rs, n) -> new Credentials(mapUser(rs, n), rs.getString("password_hash")))
                .optional();
    }

    public boolean emailExists(String email) {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE email = :email COLLATE NOCASE")
                .param("email", email).query(Long.class).single() > 0;
    }

    long insert(String email, String name, String passwordHash) {
        jdbc.sql("""
                INSERT INTO users (email, name, password_hash, created_at)
                VALUES (:email, :name, :hash, :now)
                """)
                .param("email", email).param("name", name).param("hash", passwordHash)
                .param("now", Instant.now().toString())
                .update();
        return jdbc.sql("SELECT id FROM users WHERE email = :email COLLATE NOCASE")
                .param("email", email).query(Long.class).single();
    }

    public void update(long id, String name, boolean digestEnabled) {
        jdbc.sql("UPDATE users SET name = :name, digest_enabled = :d WHERE id = :id")
                .param("name", name).param("d", digestEnabled ? 1 : 0).param("id", id).update();
    }

    void updatePassword(long id, String passwordHash) {
        jdbc.sql("UPDATE users SET password_hash = :hash WHERE id = :id")
                .param("hash", passwordHash).param("id", id).update();
    }

    /** Users who want the daily digest email. */
    public List<User> findDigestRecipients() {
        return jdbc.sql("SELECT * FROM users WHERE digest_enabled = 1 ORDER BY id")
                .query(UserRepository::mapUser).list();
    }

    private static User mapUser(ResultSet rs, int n) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                rs.getInt("digest_enabled") == 1, rs.getString("created_at"));
    }
}
