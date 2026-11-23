package com.JobHunt.appJob.pipeline;

import com.JobHunt.appJob.config.AppProperties;
import com.JobHunt.appJob.filter.JobMatch;
import com.JobHunt.appJob.filter.ProfileMatcher;
import com.JobHunt.appJob.model.Job;
import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.ProfileRepository;
import com.JobHunt.appJob.store.ProfileRow;
import com.JobHunt.appJob.store.StoredJob;
import com.JobHunt.appJob.store.UserJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Matches the shared pool of fresh stored jobs against each user's own profiles and records the
 * results in {@code user_jobs}.
 *
 * <p>Runs after every pipeline fetch (all users) and whenever a user creates, edits, deletes or
 * toggles a profile (that user only), so profile changes show up on the dashboard immediately
 * instead of waiting for the next fetch.</p>
 */
@Service
public class UserMatchService {

    private static final Logger log = LoggerFactory.getLogger(UserMatchService.class);

    /** Totals across the users matched in one call. */
    public record Result(int usersMatched, int jobsMatched, int newMatches) {}

    private final JobRepository jobRepository;
    private final ProfileRepository profileRepository;
    private final UserJobRepository userJobRepository;
    private final ProfileMatcher matcher;

    public UserMatchService(JobRepository jobRepository,
                            ProfileRepository profileRepository,
                            UserJobRepository userJobRepository,
                            AppProperties appProperties) {
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.userJobRepository = userJobRepository;
        this.matcher = new ProfileMatcher(appProperties.getMatchMinScore());
    }

    /** Re-matches fresh jobs for every user that has at least one enabled profile. */
    public Result matchAllUsers() {
        List<StoredJob> fresh = jobRepository.findFresh();
        Map<Long, List<ProfileRow>> byUser = profileRepository.findAllEnabledByUser();
        int jobsMatched = 0;
        int newMatches = 0;
        for (Map.Entry<Long, List<ProfileRow>> e : byUser.entrySet()) {
            Result r = match(e.getKey(), e.getValue(), fresh);
            jobsMatched += r.jobsMatched();
            newMatches += r.newMatches();
        }
        log.info("Matched {} fresh job(s) for {} user(s): {} match(es), {} new",
                fresh.size(), byUser.size(), jobsMatched, newMatches);
        return new Result(byUser.size(), jobsMatched, newMatches);
    }

    /** Re-matches fresh jobs for one user (after they change their profiles). */
    public Result matchUser(long userId) {
        return match(userId, profileRepository.findAllEnabled(userId), jobRepository.findFresh());
    }

    private Result match(long userId, List<ProfileRow> rows, List<StoredJob> fresh) {
        Map<String, ProfileRow> rowsByName = new HashMap<>();
        rows.forEach(r -> rowsByName.put(r.name(), r));
        var profiles = rows.stream().map(ProfileRow::toProfile).toList();

        Map<Long, UserJobRepository.Match> matches = new HashMap<>();
        List<Long> considered = fresh.stream().map(StoredJob::id).toList();
        if (!profiles.isEmpty()) {
            for (StoredJob stored : fresh) {
                Optional<JobMatch> m = matcher.bestMatch(stored.toJob(), profiles);
                if (m.isPresent() && passesProfileConstraints(m.get(), rowsByName)) {
                    matches.put(stored.id(),
                            new UserJobRepository.Match(stored.id(), m.get().profileName(), m.get().score()));
                }
            }
        }
        int inserted = userJobRepository.sync(userId, considered, matches);
        return new Result(1, matches.size(), inserted);
    }

    /**
     * Per-profile constraints beyond keyword matching: desired experience range and preferred
     * companies, taken from the profile the job matched.
     *
     * <p>Experience: if the profile sets a range and the job's extracted experience is known, the
     * job must overlap that range; unknown experience passes. Companies: if the profile lists
     * preferred companies, the job's company must contain one of them (case-insensitive).</p>
     */
    static boolean passesProfileConstraints(JobMatch m, Map<String, ProfileRow> rowsByName) {
        ProfileRow row = rowsByName.get(m.profileName());
        if (row == null) {
            return true;
        }
        Job job = m.job();
        Integer jMin = job.minExperienceYears();
        Integer jMax = job.maxExperienceYears();
        if (row.expMax() != null && jMin != null && jMin > row.expMax()) {
            return false;
        }
        if (row.expMin() != null && jMax != null && jMax < row.expMin()) {
            return false;
        }
        if (!row.companies().isEmpty() && job.company() != null) {
            String company = job.company().toLowerCase();
            return row.companies().stream().anyMatch(c -> company.contains(c.toLowerCase()));
        }
        return true;
    }
}
