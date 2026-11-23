package com.JobHunt.appJob.pipeline;

import com.JobHunt.appJob.auth.UserRepository;
import com.JobHunt.appJob.config.AppProperties;
import com.JobHunt.appJob.dedupe.JobDeduplicator;
import com.JobHunt.appJob.filter.JobMatch;
import com.JobHunt.appJob.model.Job;
import com.JobHunt.appJob.notify.DigestBuilder;
import com.JobHunt.appJob.notify.DigestEmail;
import com.JobHunt.appJob.notify.DigestNotificationService;
import com.JobHunt.appJob.notify.EmailSender;
import com.JobHunt.appJob.orchestration.RunOrchestrator;
import com.JobHunt.appJob.source.JobSource;
import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.ProfileRepository;
import com.JobHunt.appJob.store.ProfileRow;
import com.JobHunt.appJob.store.SourceRunRepository;
import com.JobHunt.appJob.store.UserJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.JobHunt.appJob.store.SqliteTestSupport.dataSource;
import static com.JobHunt.appJob.store.SqliteTestSupport.initSchema;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: stub sources → pipeline → shared jobs pool → per-user matches →
 * per-user digest.
 *
 * <ul>
 *   <li>Run 1 creates a pending match; run 2 finds nothing new (idempotent).</li>
 *   <li>A new posting on a later run shows up as one more pending match.</li>
 *   <li>Cross-source duplicates are dropped by dedupe.</li>
 *   <li>Each user only gets jobs matching their own profiles, emailed to their own address.</li>
 *   <li>After a digest, that user's matches are notified; SMTP failure leaves them pending.</li>
 *   <li>Editing a profile re-matches immediately.</li>
 * </ul>
 */
class PipelineIntegrationTest {

    @TempDir Path tempDir;

    private JdbcClient jdbc;
    private ProfileRepository profileRepo;
    private UserJobRepository userJobRepo;
    private UserMatchService userMatchService;
    private JobWatchPipeline pipeline;
    private CapturingEmailSender sender;
    private DigestNotificationService digestService;
    private DataSource ds;
    private long alice;

    private static final Instant RECENT = Instant.now().minusSeconds(1800); // 30 min ago

    private final List<Job> stubJobsA = new ArrayList<>();
    private final List<Job> stubJobsB = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ds = dataSource(tempDir.resolve("pipeline.db"));
        initSchema(ds);
        jdbc = JdbcClient.create(ds);
        JobRepository jobRepo = new JobRepository(ds);
        SourceRunRepository sourceRunRepo = new SourceRunRepository(ds);
        profileRepo = new ProfileRepository(ds);
        userJobRepo = new UserJobRepository(ds);

        AppProperties props = minimalAppProperties();
        userMatchService = new UserMatchService(jobRepo, profileRepo, userJobRepo, props);
        pipeline = new JobWatchPipeline(
                new RunOrchestrator(), new JobDeduplicator(), jobRepo, sourceRunRepo,
                new FirstRunSeeder(sourceRunRepo, jobRepo),
                List.of(new StubSource("source-a", stubJobsA), new StubSource("source-b", stubJobsB)),
                userMatchService, props);

        sender = new CapturingEmailSender(true);
        digestService = digest(sender);

        alice = user("alice@example.com");
        activeProfile(alice, "backend-java", List.of("java", "spring"));
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void run1CreatesPendingMatch_run2FindsNothing() {
        stubJobsA.add(job("a-1", "source-a", "Java Developer", RECENT));

        RunSummary s1 = pipeline.run();
        assertEquals(1, s1.newJobs(), "run 1 should produce 1 new match");
        assertEquals(1, pending(alice).size());

        RunSummary s2 = pipeline.run();
        assertEquals(0, s2.newJobs(), "run 2 must produce 0 new matches");
    }

    @Test
    void newJobOnSecondRunAppearsAsPending() {
        stubJobsA.add(job("a-1", "source-a", "Java Developer", RECENT));
        pipeline.run();

        stubJobsA.add(job("a-2", "source-a", "Spring Boot Engineer", RECENT));
        RunSummary s2 = pipeline.run();

        assertEquals(1, s2.newJobs());
        assertEquals(2, pending(alice).size());
    }

    @Test
    void crossSourceDuplicateIsDropped() {
        stubJobsA.add(job("a-10", "source-a", "Java Developer at Acme Corp", RECENT));
        stubJobsB.add(job("b-10", "source-b", "Java Developer at Acme Corp", RECENT));

        RunSummary s1 = pipeline.run();
        assertEquals(1, s1.newJobs(), "cross-source duplicate must be deduplicated to 1");
    }

    @Test
    void eachUserGetsOnlyTheirOwnMatchesAtTheirOwnAddress() {
        long bob = user("bob@example.com");
        activeProfile(bob, "golang", List.of("golang", "go developer"));
        long carol = user("carol@example.com"); // no profiles yet

        stubJobsA.add(job("a-1", "source-a", "Java Developer", RECENT));
        stubJobsA.add(job("a-2", "source-a", "Golang Developer", RECENT));
        pipeline.run();

        assertEquals(List.of("Java Developer"), titles(pending(alice)));
        assertEquals(List.of("Golang Developer"), titles(pending(bob)));
        assertTrue(pending(carol).isEmpty());

        assertTrue(digestService.sendPendingDigests(null));
        assertEquals(Map.of("alice@example.com", List.of("Java Developer"),
                "bob@example.com", List.of("Golang Developer")), sender.sent);
        assertTrue(pending(alice).isEmpty());
        assertTrue(pending(bob).isEmpty());
    }

    @Test
    void userWithDigestTurnedOffGetsNoEmail() {
        jdbc.sql("UPDATE users SET digest_enabled = 0 WHERE id = :u").param("u", alice).update();
        stubJobsA.add(job("a-1", "source-a", "Java Developer", RECENT));
        pipeline.run();

        assertTrue(digestService.sendPendingDigests(null));
        assertTrue(sender.sent.isEmpty());
        assertEquals(1, pending(alice).size(), "match stays on the dashboard");
    }

    @Test
    void smtpFailureLeavesMatchesPending() {
        DigestNotificationService failing = digest(new CapturingEmailSender(false));
        stubJobsA.add(job("a-1", "source-a", "Java Developer", RECENT));
        pipeline.run();

        assertFalse(failing.sendPendingDigests(null));
        assertEquals(1, pending(alice).size(), "matches must remain pending on SMTP failure");
    }

    @Test
    void noPendingMatchesReturnsTrue() {
        assertTrue(digestService.sendPendingDigests(null));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void editingAProfileRematchesImmediately() {
        stubJobsA.add(job("a-1", "source-a", "Java Developer", RECENT));
        stubJobsA.add(job("a-2", "source-a", "Golang Developer", RECENT));
        pipeline.run();
        assertEquals(List.of("Java Developer"), titles(pending(alice)));

        long id = profileRepo.findAll(alice).get(0).id();
        profileRepo.update(alice, id, new ProfileRow(id, "backend-java", List.of("golang"),
                List.of(), List.of(), List.of(), null, null, true));
        userMatchService.matchUser(alice);

        assertEquals(List.of("Golang Developer"), titles(pending(alice)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DigestNotificationService digest(EmailSender emailSender) {
        return new DigestNotificationService(new UserRepository(ds), userJobRepo, new JobRepository(ds),
                profileRepo, new StubDigestBuilder(), emailSender);
    }

    private long user(String email) {
        jdbc.sql("INSERT INTO users (email, password_hash, created_at) VALUES (:e, 'x', '2026-01-01T00:00:00Z')")
                .param("e", email).update();
        return jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", email).query(Long.class).single();
    }

    private void activeProfile(long userId, String name, List<String> titleKeywords) {
        long id = profileRepo.insert(userId, new ProfileRow(null, name, titleKeywords, List.of(),
                List.of("intern"), List.of(), null, null, true));
        profileRepo.toggleActive(userId, id);
    }

    private List<UserJobRepository.PendingMatch> pending(long userId) {
        return userJobRepo.findPending(userId, profileRepo.activeProfileNames(userId),
                JobRepository.freshnessCutoff(), 50);
    }

    private List<String> titles(List<UserJobRepository.PendingMatch> matches) {
        return matches.stream()
                .map(m -> jdbc.sql("SELECT title FROM jobs WHERE id = :id").param("id", m.jobId())
                        .query(String.class).single())
                .sorted().toList();
    }

    private static Job job(String id, String source, String title, Instant postedAt) {
        return Job.builder()
                .source(source).sourceJobId(id)
                .title(title).company("Acme Corp")
                .location("india").remote(false)
                .applyUrl("https://example.com/" + id)
                .description("Experience building backend services required.")
                .postedAt(postedAt).fetchedAt(Instant.now())
                .build();
    }

    /** Minimal AppProperties with permissive filters and no sources. */
    private static AppProperties minimalAppProperties() {
        var sources = new AppProperties.SourcesConfig();
        sources.getGreenhouse().setEnabled(false);
        sources.getLever().setEnabled(false);
        sources.getAshby().setEnabled(false);
        sources.getAdzuna().setEnabled(false);
        var props = new AppProperties();
        props.setProfiles(List.of());
        props.setSources(sources);
        return props;
    }

    /** Stub DigestBuilder that skips Thymeleaf rendering — only tests the pipeline wiring. */
    private static class StubDigestBuilder extends DigestBuilder {
        StubDigestBuilder() { super(null); }

        @Override
        public DigestEmail build(List<JobMatch> jobMatches, String sourceHealthLine) {
            if (jobMatches == null || jobMatches.isEmpty()) return null;
            List<Job> jobs = jobMatches.stream().map(JobMatch::job).toList();
            return DigestEmail.of("stub subject", "<html/>", "stub text",
                    jobs, Map.of("all", jobs.size()), sourceHealthLine);
        }
    }

    /** Email sender stub that records recipient → job titles, and always succeeds or fails. */
    private static class CapturingEmailSender extends EmailSender {
        private final boolean shouldSucceed;
        final Map<String, List<String>> sent = new java.util.TreeMap<>();

        CapturingEmailSender(boolean shouldSucceed) {
            super(null, new com.JobHunt.appJob.notify.EmailProperties(null, null));
            this.shouldSucceed = shouldSucceed;
        }

        @Override
        public boolean send(DigestEmail digest, String toEmail) {
            if (shouldSucceed) {
                sent.put(toEmail, digest.jobs().stream().map(Job::title).sorted().toList());
            }
            return shouldSucceed;
        }
    }

    /** Stub {@link JobSource} backed by a mutable list. */
    private record StubSource(String name, List<Job> jobs) implements JobSource {
        @Override public List<Job> fetchJobs() { return List.copyOf(jobs); }
    }
}
