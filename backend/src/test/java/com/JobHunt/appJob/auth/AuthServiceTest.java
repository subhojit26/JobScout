package com.JobHunt.appJob.auth;

import com.JobHunt.appJob.config.AppProperties;
import com.JobHunt.appJob.notify.EmailProperties;
import com.JobHunt.appJob.pipeline.UserMatchService;
import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.ProfileRepository;
import com.JobHunt.appJob.store.SqliteTestSupport;
import com.JobHunt.appJob.store.UserJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Sign-up, login, sessions, password change and owner claiming of pre-account profiles. */
class AuthServiceTest {

    @TempDir Path tempDir;

    private AuthService auth;
    private ProfileRepository profiles;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = SqliteTestSupport.dataSource(tempDir.resolve("auth.db"));
        SqliteTestSupport.initSchema(ds);
        jdbc = JdbcClient.create(ds);
        profiles = new ProfileRepository(ds);
        var matcher = new UserMatchService(new JobRepository(ds), profiles, new UserJobRepository(ds),
                new AppProperties());
        auth = new AuthService(new UserRepository(ds), new SessionRepository(ds), profiles, matcher,
                new EmailProperties("sender@example.com", "Owner@Example.com"));
    }

    private static HttpStatus status(Runnable r) {
        var e = assertThrows(ResponseStatusException.class, r::run);
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    void signUpThenLogInWithTheSameCredentials() {
        AuthService.Session s = auth.signUp("Alice", " Alice@Example.com ", "correct horse");
        assertEquals("alice@example.com", s.user().email());
        assertEquals("Alice", s.user().name());
        assertEquals(s.user().id(), auth.authenticate(s.token()).orElseThrow());

        AuthService.Session again = auth.login("ALICE@example.com", "correct horse");
        assertEquals(s.user().id(), again.user().id());
        assertNotEquals(s.token(), again.token());
    }

    @Test
    void passwordsAreHashedAndTokensAreNotStoredInPlainText() {
        AuthService.Session s = auth.signUp(null, "a@example.com", "correct horse");
        String hash = jdbc.sql("SELECT password_hash FROM users").query(String.class).single();
        assertTrue(hash.startsWith("$2"), "BCrypt hash expected");
        assertFalse(hash.contains("correct horse"));
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM sessions WHERE token_hash = :t")
                .param("t", s.token()).query(Long.class).single());
    }

    @Test
    void rejectsBadInput() {
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> auth.signUp(null, "not-an-email", "longenough")));
        assertEquals(HttpStatus.BAD_REQUEST, status(() -> auth.signUp(null, "a@example.com", "short")));
        auth.signUp(null, "a@example.com", "longenough");
        assertEquals(HttpStatus.CONFLICT, status(() -> auth.signUp(null, "A@EXAMPLE.COM", "longenough")));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() {
        auth.signUp(null, "a@example.com", "longenough");
        var wrong = assertThrows(ResponseStatusException.class, () -> auth.login("a@example.com", "nope-nope"));
        var unknown = assertThrows(ResponseStatusException.class, () -> auth.login("x@example.com", "nope-nope"));
        assertEquals(HttpStatus.UNAUTHORIZED.value(), wrong.getStatusCode().value());
        assertEquals(wrong.getReason(), unknown.getReason());
    }

    @Test
    void repeatedFailuresLockTheAccountTemporarily() {
        auth.signUp(null, "a@example.com", "longenough");
        for (int i = 0; i < 10; i++) {
            assertThrows(ResponseStatusException.class, () -> auth.login("a@example.com", "wrong-wrong"));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status(() -> auth.login("a@example.com", "longenough")));
    }

    @Test
    void logoutEndsTheSession() {
        AuthService.Session s = auth.signUp(null, "a@example.com", "longenough");
        auth.logout(s.token());
        assertTrue(auth.authenticate(s.token()).isEmpty());
        assertTrue(auth.authenticate("made-up-token").isEmpty());
        assertTrue(auth.authenticate(null).isEmpty());
    }

    @Test
    void changingPasswordSignsOutOldSessions() {
        AuthService.Session s = auth.signUp(null, "a@example.com", "longenough");
        assertEquals(HttpStatus.BAD_REQUEST,
                status(() -> auth.changePassword(s.user().id(), "wrong-current", "new-password")));
        AuthService.Session fresh = auth.changePassword(s.user().id(), "longenough", "new-password");
        assertTrue(auth.authenticate(s.token()).isEmpty());
        assertTrue(auth.authenticate(fresh.token()).isPresent());
        assertNotNull(auth.login("a@example.com", "new-password"));
    }

    @Test
    void onlyTheOwnerEmailClaimsPreAccountProfiles() {
        jdbc.sql("INSERT INTO profiles (user_id, name, enabled, created_at) VALUES (NULL, 'legacy', 1, '2026-01-01')")
                .update();
        AuthService.Session stranger = auth.signUp(null, "stranger@example.com", "longenough");
        assertTrue(profiles.findAll(stranger.user().id()).isEmpty());

        AuthService.Session owner = auth.signUp(null, "owner@example.com", "longenough");
        assertEquals(1, profiles.findAll(owner.user().id()).size());
    }
}
