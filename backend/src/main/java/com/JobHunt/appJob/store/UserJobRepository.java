package com.JobHunt.appJob.store;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Per-user job matches ({@code user_jobs}). The shared {@code jobs} table holds every eligible
 * posting once; this table records which of those matched which user's profile, and that user's
 * own notified/applied state.
 */
@Repository
public class UserJobRepository {

    /** A computed match for one job: the user's best profile name and its score. */
    public record Match(long jobId, String profile, int score) {}

    private final JdbcClient jdbc;

    public UserJobRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Makes the user's rows for {@code consideredJobIds} equal to {@code matches}: new matches are
     * inserted (pending email), existing ones get the latest profile/score, and rows for considered
     * jobs that no longer match are removed unless the user already marked them applied.
     *
     * @return number of newly inserted matches
     */
    public int sync(long userId, Collection<Long> consideredJobIds, Map<Long, Match> matches) {
        String now = Instant.now().toString();
        int inserted = 0;
        for (Match m : matches.values()) {
            boolean exists = jdbc.sql("SELECT COUNT(*) FROM user_jobs WHERE user_id = :u AND job_id = :j")
                    .param("u", userId).param("j", m.jobId()).query(Long.class).single() > 0;
            if (exists) {
                jdbc.sql("UPDATE user_jobs SET profile = :p, score = :s WHERE user_id = :u AND job_id = :j")
                        .param("p", m.profile()).param("s", m.score())
                        .param("u", userId).param("j", m.jobId()).update();
            } else {
                jdbc.sql("""
                        INSERT INTO user_jobs (user_id, job_id, profile, score, created_at)
                        VALUES (:u, :j, :p, :s, :now)
                        """)
                        .param("u", userId).param("j", m.jobId()).param("p", m.profile())
                        .param("s", m.score()).param("now", now).update();
                inserted++;
            }
        }
        for (Long jobId : consideredJobIds) {
            if (!matches.containsKey(jobId)) {
                jdbc.sql("DELETE FROM user_jobs WHERE user_id = :u AND job_id = :j AND applied_at IS NULL")
                        .param("u", userId).param("j", jobId).update();
            }
        }
        return inserted;
    }

    /**
     * Fresh, not-yet-emailed matches for the user's active profiles, newest first. Joined back to
     * {@code jobs} so the digest gets full job details.
     */
    public List<PendingMatch> findPending(long userId, List<String> activeProfiles, String freshCutoff, int limit) {
        if (activeProfiles.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT uj.job_id, uj.profile, uj.score FROM user_jobs uj JOIN jobs j ON j.id = uj.job_id
                WHERE uj.user_id = :u AND uj.notified_at IS NULL AND uj.profile IN (:profiles)
                  AND datetime(COALESCE(j.posted_at, j.fetched_at)) >= datetime(:cutoff)
                ORDER BY uj.score DESC, datetime(COALESCE(j.posted_at, j.fetched_at)) DESC
                LIMIT :limit
                """)
                .param("u", userId).param("profiles", activeProfiles)
                .param("cutoff", freshCutoff).param("limit", limit)
                .query((rs, n) -> new PendingMatch(rs.getLong("job_id"), rs.getString("profile"),
                        (Integer) rs.getObject("score")))
                .list();
    }

    public record PendingMatch(long jobId, String profile, Integer score) {}

    public void markNotified(long userId, Collection<Long> jobIds, Instant when) {
        if (jobIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE user_jobs SET notified_at = :when WHERE user_id = :u AND job_id IN (:ids)")
                .param("when", when.toString()).param("u", userId).param("ids", jobIds).update();
    }

    /** Marks one of the user's matches applied/un-applied. Returns false if it is not theirs. */
    public boolean setApplied(long userId, long jobId, boolean applied) {
        return jdbc.sql("UPDATE user_jobs SET applied_at = :ts WHERE user_id = :u AND job_id = :j")
                .param("ts", applied ? Instant.now().toString() : null)
                .param("u", userId).param("j", jobId).update() > 0;
    }
}
