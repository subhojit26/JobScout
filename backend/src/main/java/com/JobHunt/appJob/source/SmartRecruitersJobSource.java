package com.JobHunt.appJob.source;

import com.JobHunt.appJob.http.HttpJobFetcher;
import com.JobHunt.appJob.model.Job;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One company's job board on SmartRecruiters, via its public postings API (no key needed):
 * {@code GET https://api.smartrecruiters.com/v1/companies/{company}/postings?country=in&limit=100&offset=N}.
 *
 * <p>Only postings in India are requested ({@code country=in}); pages are followed up to
 * {@link #MAX_PAGES}. Many large employers in India publish here (e.g. Bosch, Swiggy,
 * ServiceNow, Freshworks). The list endpoint has no description, so the description is
 * built from the posting's function/experience/industry labels.</p>
 */
public class SmartRecruitersJobSource implements JobSource {

    public static final String SOURCE = "smartrecruiters";
    static final String DEFAULT_BASE_URL = "https://api.smartrecruiters.com";
    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 3;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** One parsed page plus the total the API reports, for pagination. */
    public record Page(List<Job> jobs, int totalFound) {}

    private final String companyId;
    private final HttpJobFetcher fetcher;
    private final String baseUrl;

    /** Parse-only constructor (fixture tests). */
    public SmartRecruitersJobSource() {
        this(null, null, DEFAULT_BASE_URL);
    }

    public SmartRecruitersJobSource(String companyId, HttpJobFetcher fetcher) {
        this(companyId, fetcher, DEFAULT_BASE_URL);
    }

    SmartRecruitersJobSource(String companyId, HttpJobFetcher fetcher, String baseUrl) {
        this.companyId = companyId;
        this.fetcher = fetcher;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public List<Job> fetchJobs() throws Exception {
        if (companyId == null || fetcher == null) {
            throw new IllegalStateException("SmartRecruitersJobSource was created parse-only.");
        }
        List<Job> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Page p = parse(fetcher.fetch(pageUrl(page * PAGE_SIZE)));
            all.addAll(p.jobs());
            if (p.jobs().isEmpty() || all.size() >= p.totalFound()) {
                break;
            }
        }
        return all;
    }

    String pageUrl(int offset) {
        return baseUrl + "/v1/companies/" + companyId + "/postings?country=in&limit=" + PAGE_SIZE
                + "&offset=" + offset;
    }

    public Page parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        List<Job> jobs = new ArrayList<>();
        Instant fetchedAt = Instant.now();
        for (JsonNode node : root.path("content")) {
            Job job = toJob(node, fetchedAt);
            if (job != null) {
                jobs.add(job);
            }
        }
        return new Page(jobs, root.path("totalFound").asInt(jobs.size()));
    }

    private Job toJob(JsonNode node, Instant fetchedAt) {
        String id = Json.text(node.path("id"));
        String title = Json.text(node.path("name"));
        if (id == null || title == null) {
            return null;
        }
        JsonNode company = node.path("company");
        String companyName = Json.text(company.path("name"));
        String companyIdentifier = Json.text(company.path("identifier"));
        if (companyIdentifier == null) {
            companyIdentifier = companyId;
        }

        JsonNode loc = node.path("location");
        String location = cleanLocation(Json.text(loc.path("fullLocation")),
                Json.text(loc.path("city")), Json.text(loc.path("country")));
        boolean remote = loc.path("remote").asBoolean(false);
        boolean hybrid = loc.path("hybrid").asBoolean(false);

        String url = companyIdentifier == null ? null
                : "https://jobs.smartrecruiters.com/" + companyIdentifier + "/" + id;

        List<String> labels = new ArrayList<>();
        for (String f : List.of("function", "experienceLevel", "industry", "department")) {
            String label = Json.text(node.path(f).path("label"));
            if (label != null && !"Other".equalsIgnoreCase(label)) {
                labels.add(label);
            }
        }

        return Job.builder()
                .source(SOURCE)
                .sourceJobId(id)
                .title(title.trim())
                .company(companyName == null ? companyIdentifier : companyName.trim())
                .location(location)
                .remote(remote)
                .workplaceType(remote ? "remote" : hybrid ? "hybrid" : "onsite")
                .applyUrl(url)
                .jobUrl(url)
                .description(String.join(". ", labels))
                .postedAt(Json.instant(Json.text(node.path("releasedDate"))))
                .employmentType(Json.text(node.path("typeOfEmployment").path("label")))
                .fetchedAt(fetchedAt)
                .build();
    }

    /** "Bengaluru, , India" → "Bengaluru, India"; falls back to city + country code. */
    static String cleanLocation(String full, String city, String country) {
        if (full != null) {
            String s = full.replaceAll("(,\\s*)+", ", ").replaceAll("^,\\s*|,\\s*$", "").trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        if (city != null) {
            return "in".equalsIgnoreCase(country) ? city + ", India" : city;
        }
        return "in".equalsIgnoreCase(country) ? "India" : null;
    }
}
