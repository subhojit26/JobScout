package com.JobHunt.appJob.auth;

import com.JobHunt.appJob.notify.EmailProperties;
import com.JobHunt.appJob.pipeline.UserMatchService;
import com.JobHunt.appJob.store.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sign-up, login and session handling.
 *
 * <p>Passwords are hashed with BCrypt. A login returns an opaque random bearer token (32 bytes);
 * only its SHA-256 hash is stored, so a leaked database cannot be replayed as sessions. Repeated
 * failed logins for one email are throttled in memory.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final Duration SESSION_TTL = Duration.ofDays(30);
    static final int MIN_PASSWORD_LENGTH = 8;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_FAILURES = 10;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    /** Result of a successful sign-up or login. */
    public record Session(String token, User user) {}

    private record Failures(int count, Instant since) {}

    private final UserRepository users;
    private final SessionRepository sessions;
    private final ProfileRepository profiles;
    private final UserMatchService userMatchService;
    private final String ownerEmail;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final String dummyHash;

    public AuthService(UserRepository users, SessionRepository sessions, ProfileRepository profiles,
                       UserMatchService userMatchService, EmailProperties emailProperties) {
        this.users = users;
        this.sessions = sessions;
        this.profiles = profiles;
        this.userMatchService = userMatchService;
        this.ownerEmail = emailProperties.to();
        this.dummyHash = encoder.encode("timing-equaliser");
    }

    public Session signUp(String name, String email, String password) {
        String cleanEmail = normaliseEmail(email);
        if (!EMAIL.matcher(cleanEmail).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address.");
        }
        checkPassword(password);
        if (users.emailExists(cleanEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        String cleanName = name == null ? "" : name.trim();
        long id = users.insert(cleanEmail, cleanName.isEmpty() ? null : cleanName, encoder.encode(password));
        log.info("New account created (user {})", id);

        // The deployment owner (JOBWATCH_EMAIL_TO) inherits the profiles made before accounts existed.
        if (ownerEmail != null && !ownerEmail.isBlank() && ownerEmail.trim().equalsIgnoreCase(cleanEmail)) {
            int claimed = profiles.claimLegacyProfiles(id);
            if (claimed > 0) {
                log.info("Assigned {} pre-account profile(s) to the owner account", claimed);
                userMatchService.matchUser(id);
            }
        }
        return newSession(users.findById(id).orElseThrow());
    }

    public Session login(String email, String password) {
        String cleanEmail = normaliseEmail(email);
        Failures f = failures.get(cleanEmail);
        if (f != null && f.count() >= MAX_FAILURES && f.since().plus(LOCKOUT).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again in 15 minutes.");
        }
        Optional<UserRepository.Credentials> creds = users.findCredentials(cleanEmail);
        // Always run BCrypt so unknown emails take as long as wrong passwords.
        boolean passwordOk = encoder.matches(password == null ? "" : password,
                creds.map(UserRepository.Credentials::passwordHash).orElse(dummyHash));
        if (creds.isEmpty() || !passwordOk) {
            failures.merge(cleanEmail, new Failures(1, Instant.now()), (old, one) ->
                    old.since().plus(LOCKOUT).isBefore(Instant.now())
                            ? one : new Failures(old.count() + 1, old.since()));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
        failures.remove(cleanEmail);
        return newSession(creds.get().user());
    }

    /** Changes the password and signs out every other session of this user. */
    public Session changePassword(long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in."));
        String hash = users.findCredentials(user.email())
                .map(UserRepository.Credentials::passwordHash).orElseThrow();
        if (!encoder.matches(currentPassword == null ? "" : currentPassword, hash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        checkPassword(newPassword);
        users.updatePassword(userId, encoder.encode(newPassword));
        sessions.deleteAllForUser(userId);
        return newSession(user);
    }

    /** Resolves a bearer token to a user id, if the session is live. */
    public Optional<Long> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findUserId(hash(token));
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.delete(hash(token));
        }
    }

    private Session newSession(User user) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.deleteExpired();
        sessions.insert(hash(token), user.id(), Instant.now().plus(SESSION_TTL));
        return new Session(token, user);
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is too long (max 72 characters).");
        }
    }

    private static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
