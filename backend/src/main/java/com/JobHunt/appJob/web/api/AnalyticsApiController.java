package com.JobHunt.appJob.web.api;

import com.JobHunt.appJob.auth.AuthInterceptor;
import com.JobHunt.appJob.pipeline.JobWatchScheduler;
import com.JobHunt.appJob.pipeline.RunSummary;
import com.JobHunt.appJob.web.AnalyticsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Jobs, analytics and manual-reload endpoints. Personal data is scoped to the signed-in user. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "jobwatch.dashboard.enabled", havingValue = "true")
public class AnalyticsApiController {

    private final AnalyticsRepository analytics;
    private final JobWatchScheduler scheduler;

    public AnalyticsApiController(AnalyticsRepository analytics, JobWatchScheduler scheduler) {
        this.analytics = analytics;
        this.scheduler = scheduler;
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics(@RequestAttribute(AuthInterceptor.USER_ID) long userId) {
        Instant now = Instant.now();
        List<AnalyticsRepository.LabelCount> seen = analytics.seenPerDay(30).reversed();
        List<AnalyticsRepository.LabelCount> emailed = analytics.emailedPerDay(userId, 30).reversed();
        return Map.ofEntries(
                Map.entry("totalJobs", analytics.totalJobs()),
                Map.entry("totalMatched", analytics.totalMatched(userId)),
                Map.entry("totalEmailed", analytics.totalEmailed(userId)),
                Map.entry("totalApplied", analytics.totalApplied(userId)),
                Map.entry("seenToday", analytics.seenSince(now.minus(Duration.ofDays(1)))),
                Map.entry("seenWeek", analytics.seenSince(now.minus(Duration.ofDays(7)))),
                Map.entry("seenMonth", analytics.seenSince(now.minus(Duration.ofDays(30)))),
                Map.entry("byProfile", analytics.countByProfile(userId)),
                Map.entry("bySource", analytics.countBySource(userId)),
                Map.entry("topCompanies", analytics.topCompanies(userId, 10)),
                Map.entry("seenSeries", seen),
                Map.entry("emailedSeries", emailed));
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs(@RequestAttribute(AuthInterceptor.USER_ID) long userId,
                                    @RequestParam(required = false) String location,
                                    @RequestParam(required = false) Boolean remote,
                                    @RequestParam(required = false) String profile,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) Boolean applied) {
        AnalyticsRepository.JobsFilter filter =
                AnalyticsRepository.JobsFilter.of(location, remote, profile, source, applied);
        return Map.of(
                "filter", filter,
                "total", analytics.filteredCount(userId, filter),
                "jobs", analytics.filteredJobs(userId, filter, 500),
                "locationFacet", analytics.facet(userId, "location", filter),
                "profileFacet", analytics.facet(userId, "profile", filter),
                "remoteFacet", analytics.remoteFacet(userId, filter));
    }

    @PostMapping("/jobs/{id}/applied")
    public ResponseEntity<Map<String, Object>> toggleApplied(@RequestAttribute(AuthInterceptor.USER_ID) long userId,
                                                             @PathVariable long id,
                                                             @RequestParam boolean applied) {
        if (!analytics.setApplied(userId, id, applied)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("id", id, "applied", applied));
    }

    /** Starts a shared fetch for everyone (one run at a time); matching then runs per user. */
    @PostMapping("/jobs/reload")
    public ResponseEntity<Map<String, Object>> reloadJobsNow() {
        boolean started = scheduler.triggerManualRunAsync();
        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("started", false, "running", true, "message", "A pipeline run is already in progress."));
        }
        return ResponseEntity.accepted().body(Map.of(
                "started", true,
                "running", true,
                "message", "Reload started. Fetching fresh jobs now."));
    }

    @GetMapping("/jobs/reload/status")
    public Map<String, Object> reloadJobsStatus(@RequestAttribute(AuthInterceptor.USER_ID) long userId) {
        AnalyticsRepository.JobsFilter filter = AnalyticsRepository.JobsFilter.of(null, null, null, null, null);
        long total = analytics.filteredCount(userId, filter);
        JobWatchScheduler.TriggerResult last = scheduler.lastManualResult();
        RunSummary summary = last.summary();

        return Map.of(
                "running", scheduler.isRunning(),
                "success", last.success(),
                "total", total,
                "rawFetched", summary == null ? 0 : summary.rawFetched(),
                "afterDedupe", summary == null ? 0 : summary.afterDedupe(),
                "eligible", summary == null ? 0 : summary.eligible(),
                "matched", summary == null ? 0 : summary.matched(),
                "newJobs", summary == null ? 0 : summary.newJobs(),
                "error", last.error() == null ? "" : last.error());
    }
}
