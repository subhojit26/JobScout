package com.JobHunt.appJob.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProfileRepository}: per-user CRUD, enabled filtering, active toggling, and
 * that one user can never read or change another user's profiles.
 */
class ProfileRepositoryTest {

    @TempDir
    Path tempDir;

    private ProfileRepository repo;
    private JdbcClient jdbc;
    private long alice;
    private long bob;

    @BeforeEach
    void setup() {
        DataSource ds = SqliteTestSupport.dataSource(tempDir.resolve("profiles.db"));
        SqliteTestSupport.initSchema(ds);
        repo = new ProfileRepository(ds);
        jdbc = JdbcClient.create(ds);
        alice = user("alice@example.com");
        bob = user("bob@example.com");
    }

    private long user(String email) {
        jdbc.sql("INSERT INTO users (email, password_hash, created_at) VALUES (:e, 'x', '2026-01-01T00:00:00Z')")
                .param("e", email).update();
        return jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(Long.class).single();
    }

    private static ProfileRow row(String name, boolean enabled) {
        return new ProfileRow(null, name,
                List.of("java", "spring"), List.of("kafka", "aws"), List.of("javascript"),
                List.of("stripe"), 0, 5, enabled);
    }

    @Test
    void insertAndReadBackRoundTrips() {
        long id = repo.insert(alice, row("backend-java", true));
        ProfileRow r = repo.findById(alice, id).orElseThrow();
        assertEquals("backend-java", r.name());
        assertTrue(r.includeTitle().contains("java"));
        assertTrue(r.stack().contains("kafka"));
        assertTrue(r.excludeTitle().contains("javascript"));
        assertTrue(r.companies().contains("stripe"));
        assertEquals(0, r.expMin());
        assertEquals(5, r.expMax());
        assertTrue(r.enabled());
    }

    @Test
    void findAllEnabledExcludesDisabled() {
        repo.insert(alice, row("on", true));
        repo.insert(alice, row("off", false));
        List<ProfileRow> enabled = repo.findAllEnabled(alice);
        assertEquals(1, enabled.size());
        assertEquals("on", enabled.get(0).name());
        assertEquals(2, repo.findAll(alice).size());
    }

    @Test
    void updateChangesFields() {
        long id = repo.insert(alice, row("p", true));
        assertTrue(repo.update(alice, id, new ProfileRow(id, "p", List.of("go"), List.of("grpc"),
                List.of(), List.of(), 2, 8, false)));
        ProfileRow r = repo.findById(alice, id).orElseThrow();
        assertTrue(r.includeTitle().contains("go"));
        assertFalse(r.enabled());
        assertEquals(2, r.expMin());
    }

    @Test
    void deleteRemoves() {
        long id = repo.insert(alice, row("p", true));
        repo.delete(alice, id);
        assertTrue(repo.findById(alice, id).isEmpty());
    }

    @Test
    void usersCanReuseNamesButNeverSeeEachOthersProfiles() {
        long a = repo.insert(alice, row("backend-java", true));
        long b = repo.insert(bob, row("backend-java", true));

        assertEquals(1, repo.findAll(alice).size());
        assertEquals(1, repo.findAll(bob).size());
        assertTrue(repo.findById(bob, a).isEmpty(), "bob must not read alice's profile");

        assertFalse(repo.update(bob, a, row("hijack", true)), "bob must not update alice's profile");
        repo.delete(bob, a);
        repo.toggleActive(bob, a);
        ProfileRow still = repo.findById(alice, a).orElseThrow();
        assertEquals("backend-java", still.name());
        assertTrue(repo.activeProfileIds(alice).isEmpty(), "bob's toggle must not touch alice's profile");
        assertTrue(repo.findById(bob, b).isPresent());
    }

    @Test
    void nameTakenIsPerUserAndCaseInsensitive() {
        long id = repo.insert(alice, row("Backend", true));
        assertTrue(repo.nameTaken(alice, "backend", null));
        assertFalse(repo.nameTaken(alice, "backend", id), "renaming a profile to its own name is fine");
        assertFalse(repo.nameTaken(bob, "backend", null));
    }

    @Test
    void ensureOneActiveActivatesFirstEnabledProfile() {
        repo.insert(alice, row("b-profile", true));
        repo.insert(alice, row("a-profile", true));
        repo.ensureOneActive(alice);
        assertEquals(List.of("a-profile"), repo.activeProfileNames(alice));
        repo.ensureOneActive(bob); // no profiles: no-op
        assertTrue(repo.activeProfileNames(bob).isEmpty());
    }

    @Test
    void legacyProfilesAreClaimedOnce() {
        jdbc.sql("INSERT INTO profiles (user_id, name, enabled, created_at) VALUES (NULL, 'legacy', 1, '2026-01-01')")
                .update();
        assertEquals(1, repo.claimLegacyProfiles(alice));
        assertEquals(0, repo.claimLegacyProfiles(bob));
        assertEquals("legacy", repo.findAll(alice).get(0).name());
    }
}
