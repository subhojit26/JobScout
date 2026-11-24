package com.JobHunt.appJob.notify;

import com.JobHunt.appJob.filter.JobMatch;
import com.JobHunt.appJob.model.Job;
import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.SqliteTestSupport;
import com.JobHunt.appJob.store.StoredJob;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders the real digest templates. Also writes {@code target/digest-preview.html/.txt} so the
 * email can be eyeballed in a browser. Set {@code JOBWATCH_PREVIEW_DB} to a jobwatch.db file to
 * preview with real postings instead of the built-in samples.
 */
class DigestBuilderTest {

    private final DigestBuilder builder = new DigestBuilder(DigestBuilder.emailTemplateEngine());

    @Test
    void emptyListBuildsNothing() {
        assertNull(builder.build(List.of(), null, "Priya"));
        assertNull(builder.build(null, null));
    }

    @Test
    void rendersGreetingGroupsCardsAndLinks() {
        DigestEmail d = builder.build(sampleMatches(), null, "Priya Sharma");
        String html = d.htmlBody();

        assertEquals("4 new job matches · 3 backend-java, 1 frontend-react", d.subject());
        assertTrue(html.contains("Good morning, Priya"), "greets by first name");
        assertTrue(html.contains("backend-java") && html.contains("frontend-react"), "profile sections");
        assertTrue(html.contains("Senior Java Engineer") && html.contains("Razorpay"));
        assertTrue(html.contains("Strong match") && html.contains("Good match"), "match badges");
        assertTrue(html.contains("₹18 L – ₹30 L / yr"), "INR salary in lakhs");
        assertTrue(html.contains("2–5 yrs"));
        assertTrue(html.contains("href=\"https://jobs.example.com/1\""), "apply link");
        assertTrue(html.contains("https://app-job-frontend-nine.vercel.app/account"), "manage email link");
        assertFalse(html.contains("${"), "no unrendered expressions");

        // Highest score first inside a profile.
        assertTrue(html.indexOf("Senior Java Engineer") < html.indexOf("Java Developer II"));
    }

    @Test
    void genericGreetingWithoutName() {
        String html = builder.build(sampleMatches(), null, null).htmlBody();
        assertTrue(html.contains("Good morning 👋"));
        assertFalse(html.contains("Good morning, "));
    }

    @Test
    void plainTextVersionIsReadable() {
        String text = builder.build(sampleMatches(), "⚠️ Failed sources this run: lever", "Priya").textBody();
        assertTrue(text.startsWith("Good morning, Priya!"));
        assertTrue(text.contains("== backend-java · 3 jobs =="));
        assertTrue(text.contains("* Senior Java Engineer — Razorpay"));
        assertTrue(text.contains("Apply: https://jobs.example.com/1"));
        assertTrue(text.contains("Failed sources this run: lever"));
        assertFalse(text.contains("th:"), "no leftover template syntax");
    }

    @Test
    void capsAndSaysSo() {
        List<JobMatch> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(match("Java Dev " + i, "Co" + i, "backend-java", 8, null, null, null, null));
        }
        DigestEmail d = builder.build(many, null, "A");
        assertEquals(50, d.jobs().size());
        assertTrue(d.htmlBody().contains("Showing your top 50 of 60 matches."));
    }

    @Test
    void formatsSalaryAndExperience() {
        assertEquals("₹8.5 L / yr", DigestBuilder.salary(850_000d, null, "INR", false));
        assertEquals("₹1.2 Cr / yr (est.)", DigestBuilder.salary(12_000_000d, null, null, true));
        assertEquals("USD 120k – USD 150k", DigestBuilder.salary(120_000d, 150_000d, "usd", false));
        assertEquals("", DigestBuilder.salary(null, null, "INR", false));
        assertEquals("3+ yrs", DigestBuilder.experience(3, null));
        assertEquals("up to 4 yrs", DigestBuilder.experience(null, 4));
        assertEquals("5 yrs", DigestBuilder.experience(5, 5));
        assertEquals("Full-time", DigestBuilder.employmentType("full_time"));
        assertEquals("Contract", DigestBuilder.employmentType("CONTRACT"));
        assertEquals("", DigestBuilder.employmentType(null));
    }

    @Test
    void writesPreviewFiles() throws Exception {
        List<JobMatch> matches = previewMatches();
        DigestEmail d = builder.build(matches, null, "Subhojit Karmakar");
        Path dir = Path.of("target");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("digest-preview.html"), d.htmlBody(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("digest-preview.txt"), d.subject() + "\n\n" + d.textBody(), StandardCharsets.UTF_8);
        assertTrue(Files.size(dir.resolve("digest-preview.html")) > 1000);
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private static List<JobMatch> previewMatches() {
        String db = System.getenv("JOBWATCH_PREVIEW_DB");
        if (db == null || db.isBlank() || !Files.exists(Path.of(db))) {
            return sampleMatches();
        }
        var repo = new JobRepository(SqliteTestSupport.dataSource(Path.of(db)));
        List<JobMatch> out = new ArrayList<>();
        for (StoredJob s : repo.findFresh()) {
            String t = s.title() == null ? "" : s.title().toLowerCase();
            String profile = t.contains("java") ? "backend-java"
                    : (t.contains("react") || t.contains("frontend")) ? "frontend-react"
                    : t.contains("golang") ? "golang" : null;
            if (profile != null) {
                int score = 5 + (int) (Math.abs((long) s.title().hashCode()) % 10);
                out.add(new JobMatch(s.toJob(), score, List.of(), profile));
            }
            if (out.size() >= 9) break;
        }
        return out.isEmpty() ? sampleMatches() : out;
    }

    private static List<JobMatch> sampleMatches() {
        return List.of(
                match("Senior Java Engineer", "Razorpay", "backend-java", 13, 2, 5, 1_800_000d, 3_000_000d),
                match("Java Developer II", "Kyndryl", "backend-java", 9, 3, null, null, null),
                match("Backend Engineer (Spring Boot)", "Swiggy", "backend-java", 6, null, null, null, null),
                match("Frontend Engineer, React", "Zepto", "frontend-react", 10, 1, 3, null, null));
    }

    private static JobMatch match(String title, String company, String profile, int score,
                                  Integer expMin, Integer expMax, Double salMin, Double salMax) {
        int n = Math.abs(title.hashCode() % 1000);
        Job job = Job.builder()
                .source("adzuna").sourceJobId(title + company)
                .title(title).company(company)
                .location(n % 2 == 0 ? "Bengaluru, Karnataka" : "Pune, Maharashtra")
                .remote(title.contains("React"))
                .applyUrl(title.startsWith("Senior Java") ? "https://jobs.example.com/1" : "https://jobs.example.com/" + n)
                .postedAt(Instant.now().minus(Duration.ofHours(2 + n % 30)))
                .fetchedAt(Instant.now())
                .minExperienceYears(expMin).maxExperienceYears(expMax)
                .salaryMin(salMin).salaryMax(salMax).salaryCurrency(salMin == null ? null : "INR")
                .employmentType(title.contains("Senior") ? "Full-time" : null)
                .stack(new LinkedHashSet<>(title.contains("React")
                        ? List.of("react", "typescript", "redux")
                        : List.of("java", "spring boot", "kafka", "aws")))
                .build();
        return new JobMatch(job, score, List.of(), profile);
    }
}
