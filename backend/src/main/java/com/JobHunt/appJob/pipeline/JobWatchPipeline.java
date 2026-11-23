package com.JobHunt.appJob.pipeline;

import com.JobHunt.appJob.config.AppProperties;
import com.JobHunt.appJob.dedupe.JobDeduplicator;
import com.JobHunt.appJob.filter.JobFilter;
import com.JobHunt.appJob.filter.Profile;
import com.JobHunt.appJob.model.Job;
import com.JobHunt.appJob.orchestration.RunOrchestrator;
import com.JobHunt.appJob.orchestration.RunResult;
import com.JobHunt.appJob.orchestration.SourceStats;
import com.JobHunt.appJob.source.JobSource;
import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.SourceRun;
import com.JobHunt.appJob.store.SourceRunRepository;
import com.JobHunt.appJob.store.StoredJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wires the full fetch→dedupe→extract→filter→match→persist pipeline for one run.
 *
 * <pre>
 *   sources
 *      ↓  RunOrchestrator (virtual threads, per-source isolation)
 *   List&lt;Job&gt;
 *      ↓  JobDeduplicator (exact + cross-source)
 *      ↓  ExtractionService (experience, stack tags)
 *      ↓  JobFilter (global eligibility)
 *      ↓  insertIfNew (shared job pool)
 *      ↓  UserMatchService (each user's profiles → user_jobs)
 *      ↓  SourceRunRepository.save (stats)
 *   RunSummary
 * </pre>
 *
 * <p>Does NOT send email; that is the responsibility of
 * {@link com.JobHunt.appJob.notify.DigestNotificationService}, invoked separately by the
 * scheduler after this method completes.</p>
 */
@Service
public class JobWatchPipeline {

    private static final Logger log = LoggerFactory.getLogger(JobWatchPipeline.class);

    private final RunOrchestrator orchestrator;
    private final JobDeduplicator deduplicator;
    private final ExtractionService extraction;
    private final JobFilter filter;
    private final List<Profile> profiles;
    private final List<String> stackKeywords;
    private final JobRepository jobRepository;
    private final SourceRunRepository sourceRunRepository;
    private final FirstRunSeeder firstRunSeeder;
    private final List<JobSource> sources;                 // static list (test mode); null in prod
    private final JobSourceProvider sourceProvider;        // dynamic roster (prod); null in tests
    private final CompanyDiscoveryService discoveryService; // nullable (tests)
    private final ProfileProvider profileProvider;          // DB-backed profiles (prod); null in tests
    private final UserMatchService userMatchService;        // per-user matching (prod); may be null in tests

    /** Production constructor: sources come from the discovered roster, with company discovery. */
    @org.springframework.beans.factory.annotation.Autowired
    public JobWatchPipeline(
            RunOrchestrator orchestrator,
            JobDeduplicator deduplicator,
            JobRepository jobRepository,
            SourceRunRepository sourceRunRepository,
            FirstRunSeeder firstRunSeeder,
            JobSourceProvider sourceProvider,
            CompanyDiscoveryService discoveryService,
            ProfileProvider profileProvider,
            UserMatchService userMatchService,
            AppProperties appProperties) {
        this(orchestrator, deduplicator, jobRepository, sourceRunRepository, firstRunSeeder,
                null, sourceProvider, discoveryService, profileProvider, userMatchService, appProperties);
    }

    /** Test constructor: a fixed list of stub sources, no discovery. */
    public JobWatchPipeline(
            RunOrchestrator orchestrator,
            JobDeduplicator deduplicator,
            JobRepository jobRepository,
            SourceRunRepository sourceRunRepository,
            FirstRunSeeder firstRunSeeder,
            List<JobSource> sources,
            UserMatchService userMatchService,
            AppProperties appProperties) {
        this(orchestrator, deduplicator, jobRepository, sourceRunRepository, firstRunSeeder,
                sources, null, null, null, userMatchService, appProperties);
    }

    private JobWatchPipeline(
            RunOrchestrator orchestrator,
            JobDeduplicator deduplicator,
            JobRepository jobRepository,
            SourceRunRepository sourceRunRepository,
            FirstRunSeeder firstRunSeeder,
            List<JobSource> sources,
            JobSourceProvider sourceProvider,
            CompanyDiscoveryService discoveryService,
            ProfileProvider profileProvider,
            UserMatchService userMatchService,
            AppProperties appProperties) {

        this.userMatchService = userMatchService;
        this.orchestrator = orchestrator;
        this.deduplicator = deduplicator;
        this.jobRepository = jobRepository;
        this.sourceRunRepository = sourceRunRepository;
        this.firstRunSeeder = firstRunSeeder;
        this.sources = sources;
        this.sourceProvider = sourceProvider;
        this.discoveryService = discoveryService;
        this.profileProvider = profileProvider;
        this.extraction = new ExtractionService();

        // Build filter and profile list from typed config
        this.filter = new JobFilter(appProperties.getFilters().toFilterProfile());

        this.profiles = appProperties.toProfiles();

        // Collect all stack keywords across all profiles for extraction
        this.stackKeywords = profiles.stream()
                .flatMap(p -> p.includeDescriptionKeywords().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Executes one full run and returns a summary.
     *
     * @return a {@link RunSummary} describing what happened
     */
    public RunSummary run() {
        return run(true);
    }

    /**
     * Executes one full run and returns a summary.
     *
     * @param allowDiscovery when {@code false}, skips company-board discovery probing. Manual
     *                       dashboard reloads pass {@code false} to keep the run light (less
     *                       memory/CPU), avoiding OOM-driven 502s on small containers. The 6am
     *                       scheduled run passes {@code true} to keep the roster fresh.
     * @return a {@link RunSummary} describing what happened
     */
    public RunSummary run(boolean allowDiscovery) {
        Instant runStart = Instant.now();

        // Resolve sources for this run: dynamic DB-backed roster (prod) or static list (tests).
        List<JobSource> runSources = (sourceProvider != null)
                ? sourceProvider.currentSources()
                : sources;

        // 0. Bootstrap the roster BEFORE fetching when it is empty (first run / fresh DB).
        //    Otherwise the fetch below would run against 0 sources and return 0 jobs, and the
        //    newly-discovered boards would only take effect on the NEXT run (the reason a fresh
        //    reload previously yielded no jobs). discoverIfNeeded is self-gated (no-op when the
        //    roster already has companies) and memory-bounded.
        if (allowDiscovery && discoveryService != null && runSources.isEmpty()) {
            try {
                log.info("Empty company roster detected — bootstrapping discovery before fetch");
                discoveryService.discoverIfNeeded(List.of());
            } catch (Exception e) {
                log.warn("Pre-fetch company discovery failed (non-fatal): {}", e.toString());
            }
            // Re-resolve sources now that the roster may have been populated.
            runSources = (sourceProvider != null) ? sourceProvider.currentSources() : sources;
        }
        log.info("Pipeline run started with {} configured source(s)", runSources.size());

        // 1. Fetch concurrently
        RunResult result = orchestrator.run(runSources);

        // 1b. Company discovery (Phase 6): harvest additional boards from this run's Adzuna company
        //     names. Still self-gated by an empty roster, so it is a no-op once bootstrapped above.
        if (allowDiscovery && discoveryService != null) {
            List<String> harvested = result.jobs().stream()
                    .filter(j -> "adzuna".equals(j.source()))
                    .map(Job::company)
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .collect(Collectors.toList());
            try {
                discoveryService.discoverIfNeeded(harvested);
            } catch (Exception e) {
                log.warn("Company discovery failed (non-fatal): {}", e.toString());
            }
        }

        // 2. Record source run stats immediately
        Instant runEnd = Instant.now();
        for (SourceStats stats : result.sourceStats()) {
            sourceRunRepository.save(SourceRun.fromStats(stats, runStart, runEnd));
            if (!stats.success()) {
                log.warn("[{}] fetch failed: {}", stats.sourceName(),
                        stats.error() != null ? stats.error().getMessage() : "unknown error");
            } else {
                log.info("[{}] fetched {} job(s)", stats.sourceName(), stats.jobsFetched());
            }
        }

        List<Job> allJobs = result.jobs();
        log.info("Total raw jobs fetched: {}", allJobs.size());

        // 3. Deduplicate in-memory (across sources)
        List<Job> deduped = deduplicator.deduplicate(allJobs);
        log.info("After dedupe: {} job(s)", deduped.size());

        // Stack keywords for extraction: union of every user's enabled profiles (DB) or config (tests).
        List<String> runStackKeywords = (profileProvider != null)
                ? profileProvider.allStackKeywords()
                : this.stackKeywords;
        if (runStackKeywords.isEmpty()) {
            runStackKeywords = this.stackKeywords;
        }
        final List<String> extractionKeywords = runStackKeywords;

        // 4. Enrich with extraction (experience, stack)
        List<Job> enriched = deduped.stream()
                .map(job -> {
                    EnrichedJob e = extraction.enrich(job, extractionKeywords);
                    // Re-build job with extracted experience/stack merged in
                    return Job.builder()
                            .source(job.source())
                            .sourceJobId(job.sourceJobId())
                            .title(job.title())
                            .company(job.company())
                            .location(job.location())
                            .remote(job.remote())
                            .workplaceType(job.workplaceType())
                            .applyUrl(job.applyUrl())
                            .jobUrl(job.jobUrl())
                            .description(job.description())
                            .postedAt(job.postedAt())
                            .minExperienceYears(e.expMin())
                            .maxExperienceYears(e.expMax())
                            .salaryMin(job.salaryMin())
                            .salaryMax(job.salaryMax())
                            .salaryCurrency(job.salaryCurrency())
                            .salaryEstimated(job.salaryEstimated())
                            .employmentType(job.employmentType())
                            .fetchedAt(job.fetchedAt())
                            .stack(e.stack())
                            .build();
                })
                .collect(Collectors.toList());

        // 5. Global filter
        List<Job> eligible = filter.filter(enriched);
        log.info("After global filter: {} eligible job(s)", eligible.size());

        // 6. Persist every eligible job once into the shared pool. Per-user state lives in
        //    user_jobs, so the legacy notified_at column is stamped to keep old readers inert.
        int stored = 0;
        Instant now = Instant.now();
        for (Job job : eligible) {
            if (jobRepository.insertIfNew(StoredJob.fromJob(job).withNotifiedAt(now))) {
                stored++;
            }
        }
        log.info("Stored {} new eligible job(s)", stored);

        // 7. Match the fresh pool against each user's own profiles.
        int matchedCount = 0;
        int newJobs = 0;
        if (userMatchService != null) {
            UserMatchService.Result r = userMatchService.matchAllUsers();
            matchedCount = r.jobsMatched();
            newJobs = r.newMatches();
        }

        log.info("Pipeline run complete: {} new per-user match(es) pending notification", newJobs);

        // Build health line for any failing sources
        String healthLine = buildHealthLine(result.sourceStats());

        return new RunSummary(
                result.sourceStats(),
                allJobs.size(),
                deduped.size(),
                eligible.size(),
                matchedCount,
                newJobs,
                healthLine
        );
    }

    private static String buildHealthLine(List<SourceStats> stats) {
        List<String> failures = stats.stream()
                .filter(s -> !s.success())
                .map(s -> s.sourceName())
                .collect(Collectors.toList());
        if (failures.isEmpty()) {
            return null;
        }
        return "⚠️ Failed sources this run: " + String.join(", ", failures);
    }
}

