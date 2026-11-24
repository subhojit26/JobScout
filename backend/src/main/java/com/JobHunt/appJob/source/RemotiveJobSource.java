package com.JobHunt.appJob.source;

import com.JobHunt.appJob.http.HttpJobFetcher;
import com.JobHunt.appJob.model.Job;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Remote software jobs from Remotive's free public API (no key needed):
 * {@code GET https://remotive.com/api/remote-jobs?category=software-dev}.
 *
 * <p>Remotive asks clients not to call the API more than a few times a day, so this makes one
 * request per run for the whole software category and reuses the result for {@link #MIN_INTERVAL}.
 * Only postings whose required location is worldwide or covers India/APAC are kept. Apply links
 * point at the posting on Remotive, as their terms require.</p>
 */
public class RemotiveJobSource implements JobSource {

    public static final String SOURCE = "remotive";
    static final String DEFAULT_URL = "https://remotive.com/api/remote-jobs?category=software-dev";
    static final Duration MIN_INTERVAL = Duration.ofHours(6);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern OPEN_TO_INDIA =
            Pattern.compile("worldwide|anywhere|global|india|apac|asia", Pattern.CASE_INSENSITIVE);

    private static volatile Instant lastFetch;
    private static volatile List<Job> cached = List.of();

    private final HttpJobFetcher fetcher;
    private final String url;

    /** Parse-only constructor (fixture tests). */
    public RemotiveJobSource() {
        this(null, DEFAULT_URL);
    }

    public RemotiveJobSource(HttpJobFetcher fetcher) {
        this(fetcher, DEFAULT_URL);
    }

    RemotiveJobSource(HttpJobFetcher fetcher, String url) {
        this.fetcher = fetcher;
        this.url = url;
    }

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public List<Job> fetchJobs() throws Exception {
        if (fetcher == null) {
            throw new IllegalStateException("RemotiveJobSource was created parse-only.");
        }
        Instant last = lastFetch;
        if (last != null && last.plus(MIN_INTERVAL).isAfter(Instant.now())) {
            return cached; // respect Remotive's "a few calls per day" request
        }
        List<Job> jobs = parse(fetcher.fetch(url));
        cached = jobs;
        lastFetch = Instant.now();
        return jobs;
    }

    /** Parses the API response, keeping postings open to candidates in India. */
    public List<Job> parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        List<Job> jobs = new ArrayList<>();
        Instant fetchedAt = Instant.now();
        for (JsonNode node : root.path("jobs")) {
            String where = Json.text(node.path("candidate_required_location"));
            if (where != null && !OPEN_TO_INDIA.matcher(where).find()) {
                continue;
            }
            String id = Json.text(node.path("id"));
            String title = Json.text(node.path("title"));
            String link = Json.text(node.path("url"));
            if (id == null || title == null || link == null) {
                continue;
            }
            String html = Json.text(node.path("description"));
            jobs.add(Job.builder()
                    .source(SOURCE)
                    .sourceJobId(id)
                    .title(title.trim())
                    .company(Json.text(node.path("company_name")))
                    .location("Remote (" + (where == null ? "Worldwide" : where.trim()) + ")")
                    .remote(true)
                    .workplaceType("remote")
                    .applyUrl(link)
                    .jobUrl(link)
                    .description(html == null ? null : Jsoup.parse(html).text())
                    .postedAt(Json.instant(Json.text(node.path("publication_date"))))
                    .employmentType(Json.text(node.path("job_type")))
                    .fetchedAt(fetchedAt)
                    .build());
        }
        return jobs;
    }

    /** Test hook: forget the cached response. */
    static void resetCache() {
        lastFetch = null;
        cached = List.of();
    }
}
