package com.JobHunt.appJob.notify;

import com.JobHunt.appJob.config.AppProperties;
import com.JobHunt.appJob.filter.JobMatch;
import com.JobHunt.appJob.model.Job;
import com.JobHunt.appJob.util.TimeAgo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds HTML and plain-text digest emails from a list of matched jobs.
 *
 * <p>Jobs are grouped by the profile they matched (largest group first), and within a group
 * sorted by match score, then recency. The templates get a ready-to-render view model
 * ({@link Section}/{@link Card}) so they stay free of formatting logic. The cap comes from
 * {@code jobwatch.digest.max-jobs} (default 50).</p>
 */
@Service
public class DigestBuilder {

    static final String DEFAULT_DASHBOARD_URL = "https://app-job-frontend-nine.vercel.app";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);

    /** One job, pre-formatted for the email. */
    public record Card(String title, String company, String initial, String location, boolean remote,
                       String posted, String experience, String salary, String employmentType,
                       List<String> stack, String applyUrl, int score, String matchLabel,
                       String matchColor, String matchBackground) {

        /** "💼 2–5 yrs · 💰 ₹12 L / yr · Full-time" with only the parts that are known. */
        public String details() {
            List<String> parts = new ArrayList<>();
            if (!experience.isEmpty()) parts.add("💼 " + experience);
            if (!salary.isEmpty()) parts.add("💰 " + salary);
            if (!employmentType.isEmpty()) parts.add(employmentType);
            return String.join("  ·  ", parts);
        }

        /** Same as {@link #details()} without icons, for the plain-text email. */
        public String plainDetails() {
            List<String> parts = new ArrayList<>(List.of(matchLabel));
            if (!location.isEmpty()) parts.add(location);
            if (remote) parts.add("Remote");
            if (!posted.isEmpty()) parts.add(posted);
            if (!experience.isEmpty()) parts.add(experience);
            if (!salary.isEmpty()) parts.add(salary);
            if (!employmentType.isEmpty()) parts.add(employmentType);
            return String.join(" · ", parts);
        }
    }

    /** The jobs that matched one profile. */
    public record Section(String profile, int count, List<Card> cards) {}

    private final ITemplateEngine templateEngine;
    private final int maxJobs;
    private final String dashboardUrl;

    /** Production constructor: cap and dashboard link from configuration. */
    @Autowired
    public DigestBuilder(AppProperties appProperties,
                         @Value("${jobwatch.dashboard-url:" + DEFAULT_DASHBOARD_URL + "}") String dashboardUrl) {
        this.templateEngine = emailTemplateEngine();
        this.maxJobs = appProperties.getDigest().getMaxJobs();
        this.dashboardUrl = dashboardUrl.replaceAll("/+$", "");
    }

    /** Test/stub constructor: default cap and dashboard link. */
    public DigestBuilder(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        this.maxJobs = 50;
        this.dashboardUrl = DEFAULT_DASHBOARD_URL;
    }

    /**
     * A template engine just for emails: {@code templates/*.html} in HTML mode and
     * {@code templates/*.txt} in TEXT mode, independent of the web view settings.
     */
    public static ITemplateEngine emailTemplateEngine() {
        ClassLoaderTemplateResolver html = new ClassLoaderTemplateResolver();
        html.setPrefix("templates/");
        html.setTemplateMode(TemplateMode.HTML);
        html.setCharacterEncoding("UTF-8");
        html.setResolvablePatterns(Set.of("*.html"));
        html.setOrder(1);

        ClassLoaderTemplateResolver text = new ClassLoaderTemplateResolver();
        text.setPrefix("templates/");
        text.setTemplateMode(TemplateMode.TEXT);
        text.setCharacterEncoding("UTF-8");
        text.setResolvablePatterns(Set.of("*.txt"));
        text.setOrder(2);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.addTemplateResolver(html);
        engine.addTemplateResolver(text);
        return engine;
    }

    /** Builds a digest without a personal greeting. */
    public DigestEmail build(List<JobMatch> jobMatches, String sourceHealthLine) {
        return build(jobMatches, sourceHealthLine, null);
    }

    /**
     * Builds a digest email. Returns {@code null} when the list is empty (no email is sent then).
     *
     * @param recipientName first line of the greeting; null/blank for a generic greeting
     */
    public DigestEmail build(List<JobMatch> jobMatches, String sourceHealthLine, String recipientName) {
        if (jobMatches == null || jobMatches.isEmpty()) {
            return null;
        }

        Comparator<JobMatch> byScoreThenRecency = Comparator
                .comparingInt((JobMatch jm) -> -jm.score())
                .thenComparing(DigestBuilder::when, Comparator.nullsLast(Comparator.reverseOrder()));

        // Group by profile, biggest group first; cap across all groups in that order.
        Map<String, List<JobMatch>> grouped = jobMatches.stream()
                .collect(Collectors.groupingBy(jm -> jm.profileName() != null ? jm.profileName() : "Other",
                        LinkedHashMap::new, Collectors.toList()));
        List<Map.Entry<String, List<JobMatch>>> groups = new ArrayList<>(grouped.entrySet());
        groups.sort(Comparator.comparingInt((Map.Entry<String, List<JobMatch>> e) -> -e.getValue().size())
                .thenComparing(Map.Entry::getKey));

        Map<String, Integer> jobsByProfile = new LinkedHashMap<>();
        List<Section> sections = new ArrayList<>();
        List<Job> cappedJobs = new ArrayList<>();
        int remaining = maxJobs;
        for (Map.Entry<String, List<JobMatch>> g : groups) {
            jobsByProfile.put(g.getKey(), g.getValue().size());
            if (remaining <= 0) {
                continue;
            }
            List<JobMatch> sorted = g.getValue().stream().sorted(byScoreThenRecency)
                    .limit(remaining).toList();
            remaining -= sorted.size();
            sorted.forEach(jm -> cappedJobs.add(jm.job()));
            sections.add(new Section(g.getKey(), g.getValue().size(),
                    sorted.stream().map(DigestBuilder::card).toList()));
        }

        int total = jobMatches.size();
        boolean isCapped = total > cappedJobs.size();
        String subject = buildSubject(total, jobsByProfile);

        Context ctx = new Context();
        ctx.setVariable("sections", sections);
        ctx.setVariable("totalJobs", total);
        ctx.setVariable("shownJobs", cappedJobs.size());
        ctx.setVariable("isCapped", isCapped);
        ctx.setVariable("healthLine", sourceHealthLine != null ? sourceHealthLine : "");
        ctx.setVariable("greetingName", firstName(recipientName));
        ctx.setVariable("today", DATE.format(Instant.now().atZone(IST)));
        ctx.setVariable("preheader", preheader(sections));
        ctx.setVariable("dashboardUrl", dashboardUrl);

        String htmlBody = templateEngine.process("digest.html", ctx);
        String textBody = templateEngine.process("digest.txt", ctx);
        return DigestEmail.of(subject, htmlBody, textBody, cappedJobs, jobsByProfile, sourceHealthLine);
    }

    // ── formatting helpers ──────────────────────────────────────────────────

    static String buildSubject(int total, Map<String, Integer> jobsByProfile) {
        String head = total + " new job match" + (total == 1 ? "" : "es");
        if (jobsByProfile.isEmpty()) {
            return head;
        }
        return head + " · " + jobsByProfile.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String preheader(List<Section> sections) {
        return sections.stream().flatMap(s -> s.cards().stream()).limit(3)
                .map(c -> c.title() + (c.company().isEmpty() ? "" : " at " + c.company()))
                .collect(Collectors.joining(" · "));
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim().split("\\s+")[0];
    }

    private static Instant when(JobMatch jm) {
        return jm.job().postedAt() != null ? jm.job().postedAt() : jm.job().fetchedAt();
    }

    static Card card(JobMatch jm) {
        Job j = jm.job();
        String company = j.company() == null ? "" : j.company().trim();
        String initial = company.isEmpty() ? "?" : company.substring(0, 1).toUpperCase(Locale.ROOT);
        Instant when = when(jm);
        int score = jm.score();
        String label;
        String color;
        String bg;
        if (score >= 12) {
            label = "Strong match";
            color = "#0f7a4f";
            bg = "#e3f7ee";
        } else if (score >= 8) {
            label = "Good match";
            color = "#4338ca";
            bg = "#eceafe";
        } else {
            label = "Match";
            color = "#5b5873";
            bg = "#f1f0f6";
        }
        List<String> stack = j.stack() == null ? List.of()
                : j.stack().stream().filter(s -> s != null && !s.isBlank()).limit(6).toList();
        return new Card(
                j.title() == null ? "Untitled role" : j.title().trim(),
                company,
                initial,
                j.location() == null ? "" : j.location().trim(),
                j.remote(),
                when == null ? "" : TimeAgo.ago(when),
                experience(j.minExperienceYears(), j.maxExperienceYears()),
                salary(j.salaryMin(), j.salaryMax(), j.salaryCurrency(), j.salaryEstimated()),
                employmentType(j.employmentType()),
                stack,
                j.applyUrl() != null ? j.applyUrl() : (j.jobUrl() != null ? j.jobUrl() : ""),
                score, label, color, bg);
    }

    /** "full_time" / "FULL-TIME" / "permanent" → "Full-time"; anything else is title-cased. */
    static String employmentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String k = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return switch (k) {
            case "full-time", "fulltime", "permanent" -> "Full-time";
            case "part-time", "parttime" -> "Part-time";
            case "contract", "contractor" -> "Contract";
            case "intern", "internship" -> "Internship";
            case "temporary", "temp" -> "Temporary";
            default -> Character.toUpperCase(k.charAt(0)) + k.substring(1).replace('-', ' ');
        };
    }

    static String experience(Integer min, Integer max) {
        if (min == null && max == null) {
            return "";
        }
        if (min != null && max != null) {
            return min.equals(max) ? min + " yrs" : min + "–" + max + " yrs";
        }
        return min != null ? min + "+ yrs" : "up to " + max + " yrs";
    }

    static String salary(Double min, Double max, String currency, boolean estimated) {
        if (min == null && max == null) {
            return "";
        }
        boolean inr = currency == null || currency.isBlank() || "INR".equalsIgnoreCase(currency);
        String lo = min == null ? null : money(min, inr, currency);
        String hi = max == null ? null : money(max, inr, currency);
        String range = (lo != null && hi != null && !lo.equals(hi)) ? lo + " – " + hi : (lo != null ? lo : hi);
        return range + (inr ? " / yr" : "") + (estimated ? " (est.)" : "");
    }

    private static String money(double v, boolean inr, String currency) {
        if (inr) {
            if (v >= 10_000_000) {
                return "₹" + trim(v / 10_000_000) + " Cr";
            }
            if (v >= 100_000) {
                return "₹" + trim(v / 100_000) + " L";
            }
            return "₹" + String.format(Locale.ROOT, "%,.0f", v);
        }
        String prefix = currency.toUpperCase(Locale.ROOT) + " ";
        return v >= 1000 ? prefix + trim(v / 1000) + "k" : prefix + String.format(Locale.ROOT, "%.0f", v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
