package com.JobHunt.appJob.source;

import com.JobHunt.appJob.http.HttpJobFetcher;
import com.JobHunt.appJob.model.Job;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Remote jobs from Himalayas' free public search API (no key needed):
 * {@code GET https://himalayas.app/jobs/api/search?q={query}}.
 *
 * <p>Each posting lists the countries it hires from ({@code locationRestrictions}). Only
 * postings open worldwide (empty list) or including India are kept, so US/EU-only remote
 * roles never reach the user.</p>
 */
public class HimalayasJobSource implements JobSource {

    public static final String SOURCE = "himalayas";
    static final String DEFAULT_BASE_URL = "https://himalayas.app";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String query;
    private final HttpJobFetcher fetcher;
    private final String baseUrl;

    /** Parse-only constructor (fixture tests). */
    public HimalayasJobSource() {
        this(null, null, DEFAULT_BASE_URL);
    }

    public HimalayasJobSource(String query, HttpJobFetcher fetcher) {
        this(query, fetcher, DEFAULT_BASE_URL);
    }

    HimalayasJobSource(String query, HttpJobFetcher fetcher, String baseUrl) {
        this.query = query;
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public List<Job> fetchJobs() throws Exception {
        if (query == null || fetcher == null) {
            throw new IllegalStateException("HimalayasJobSource was created parse-only.");
        }
        return parse(fetcher.fetch(searchUrl()));
    }

    String searchUrl() {
        return baseUrl + "/jobs/api/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    /** Parses a search response, keeping only postings open to India or worldwide. */
    public List<Job> parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        List<Job> jobs = new ArrayList<>();
        Instant fetchedAt = Instant.now();
        for (JsonNode node : root.path("jobs")) {
            String scope = eligibleScope(node.path("locationRestrictions"));
            if (scope == null) {
                continue;
            }
            Job job = toJob(node, scope, fetchedAt);
            if (job != null) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    /** "Worldwide" / "India", or {@code null} when India candidates cannot apply. */
    static String eligibleScope(JsonNode restrictions) {
        if (restrictions == null || !restrictions.isArray() || restrictions.isEmpty()) {
            return "Worldwide";
        }
        for (JsonNode c : restrictions) {
            if ("india".equalsIgnoreCase(Json.text(c))) {
                return "India";
            }
        }
        return null;
    }

    private Job toJob(JsonNode node, String scope, Instant fetchedAt) {
        String title = Json.text(node.path("title"));
        String guid = Json.text(node.path("guid"));
        if (title == null || guid == null) {
            return null;
        }
        String html = Json.text(node.path("description"));
        String description = html != null ? Jsoup.parse(html).text() : Json.text(node.path("excerpt"));

        Double min = Json.number(node.path("minSalary"));
        Double max = Json.number(node.path("maxSalary"));
        String period = Json.text(node.path("salaryPeriod"));
        double factor = period == null ? 0
                : switch (period.toLowerCase(Locale.ROOT)) {
                    case "annual", "yearly", "year" -> 1;
                    case "monthly", "month" -> 12;
                    default -> 0; // hourly/unknown: don't guess an annual figure
                };
        boolean hasSalary = factor > 0 && (min != null || max != null);

        long pub = node.path("pubDate").asLong(0);
        String apply = Json.text(node.path("applicationLink"));

        return Job.builder()
                .source(SOURCE)
                .sourceJobId(guid)
                .title(title.trim())
                .company(Json.text(node.path("companyName")))
                .location("Remote (" + scope + ")")
                .remote(true)
                .workplaceType("remote")
                .applyUrl(apply != null ? apply : guid)
                .jobUrl(guid)
                .description(description)
                .postedAt(pub > 0 ? Instant.ofEpochSecond(pub) : null)
                .employmentType(Json.text(node.path("employmentType")))
                .salaryMin(hasSalary && min != null ? min * factor : null)
                .salaryMax(hasSalary && max != null ? max * factor : null)
                .salaryCurrency(hasSalary ? Json.text(node.path("currency")) : null)
                .fetchedAt(fetchedAt)
                .build();
    }
}
