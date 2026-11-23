package com.JobHunt.appJob.web;

import com.JobHunt.appJob.store.JobRepository;
import com.JobHunt.appJob.store.ProfileRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read queries for the dashboard. Market-wide numbers (openings seen, sources) come from the
 * shared {@code jobs} table; everything personal (matches, emailed, applied, per-profile) comes
 * from the signed-in user's {@code user_jobs} rows.
 */
@Repository
public class AnalyticsRepository {

    /** A label with an associated count (per-profile, per-source, per-day, etc.). */
    public record LabelCount(String label, long count) {}

    /** A compact job row for the jobs table. {@code id} is the shared job id. */
    public record JobRow(long id, String title, String company, String location, boolean remote,
                         String source, String profile, Integer score, String applyUrl,
                         String when, String postedAt, boolean applied) {}

    /** Selected filters for the jobs table. Any field may be null (= not filtered). */
    public record JobsFilter(String location, Boolean remote, String profile, String source,
                             Boolean applied) {
        public static JobsFilter of(String location, Boolean remote, String profile,
                                    String source, Boolean applied) {
            String loc = (location == null || location.isBlank()) ? null : location;
            String prof = (profile == null || profile.isBlank()) ? null : profile;
            String src = (source == null || source.isBlank()) ? null : source;
            return new JobsFilter(loc, remote, prof, src, applied);
        }
        public boolean isEmpty() {
            return location == null && remote == null && profile == null && source == null && applied == null;
        }
    }

    private static final String MINE = " FROM user_jobs uj JOIN jobs j ON j.id = uj.job_id WHERE uj.user_id = :u ";

    private final JdbcClient jdbc;
    private final ProfileRepository profiles;

    public AnalyticsRepository(DataSource dataSource, ProfileRepository profiles) {
        this.jdbc = JdbcClient.create(dataSource);
        this.profiles = profiles;
    }

    // ── market-wide ──────────────────────────────────────────────────────────

    public long totalJobs() {
        return jdbc.sql("SELECT COUNT(*) FROM jobs").query(Long.class).single();
    }

    /** Openings seen since {@code cutoff}, by posted date (falling back to fetched date). */
    public long seenSince(Instant cutoff) {
        return jdbc.sql("SELECT COUNT(*) FROM jobs WHERE COALESCE(posted_at, fetched_at) >= :cutoff")
                .param("cutoff", cutoff.toString()).query(Long.class).single();
    }

    /** New openings seen per calendar day (UTC), most recent first, limited to {@code days}. */
    public List<LabelCount> seenPerDay(int days) {
        return jdbc.sql("""
                SELECT substr(COALESCE(posted_at, fetched_at), 1, 10) AS label, COUNT(*) AS c
                FROM jobs WHERE COALESCE(posted_at, fetched_at) IS NOT NULL
                GROUP BY label ORDER BY label DESC LIMIT :days
                """)
                .param("days", days).query(AnalyticsRepository::labelCount).list();
    }

    // ── personal ─────────────────────────────────────────────────────────────

    public long totalMatched(long userId) {
        return count("SELECT COUNT(*)" + MINE, userId);
    }

    public long totalEmailed(long userId) {
        return count("SELECT COUNT(*)" + MINE + "AND uj.notified_at IS NOT NULL", userId);
    }

    public long totalApplied(long userId) {
        return count("SELECT COUNT(*)" + MINE + "AND uj.applied_at IS NOT NULL", userId);
    }

    public List<LabelCount> countByProfile(long userId) {
        return grouped("SELECT uj.profile AS label, COUNT(*) AS c" + MINE
                + "GROUP BY uj.profile ORDER BY c DESC", userId);
    }

    public List<LabelCount> countBySource(long userId) {
        return grouped("SELECT j.source AS label, COUNT(*) AS c" + MINE
                + "GROUP BY j.source ORDER BY c DESC", userId);
    }

    public List<LabelCount> topCompanies(long userId, int limit) {
        return grouped("SELECT COALESCE(j.company, '(unknown)') AS label, COUNT(*) AS c" + MINE
                + "GROUP BY j.company ORDER BY c DESC LIMIT " + Math.max(1, limit), userId);
    }

    /** The user's emailed jobs per calendar day (UTC), most recent first. */
    public List<LabelCount> emailedPerDay(long userId, int days) {
        return grouped("SELECT substr(uj.notified_at, 1, 10) AS label, COUNT(*) AS c" + MINE
                + "AND uj.notified_at IS NOT NULL GROUP BY label ORDER BY label DESC LIMIT "
                + Math.max(1, days), userId);
    }

    /** Marks one of the user's matched jobs applied/un-applied. */
    public boolean setApplied(long userId, long jobId, boolean applied) {
        return jdbc.sql("UPDATE user_jobs SET applied_at = :ts WHERE user_id = :u AND job_id = :j")
                .param("ts", applied ? Instant.now().toString() : null)
                .param("u", userId).param("j", jobId).update() > 0;
    }

    // ── jobs table with faceted filters ─────────────────────────────────────

    /**
     * WHERE clause over the user's fresh matches for their active profiles, optionally skipping
     * one dimension (used when counting that dimension's own facet).
     */
    private String buildWhere(long userId, JobsFilter f, String skip, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(MINE);
        params.put("u", userId);
        sb.append("AND datetime(COALESCE(j.posted_at, j.fetched_at)) >= datetime(:freshCutoff)");
        params.put("freshCutoff", JobRepository.freshnessCutoff());
        List<String> activeNames = profiles.activeProfileNames(userId);
        if (activeNames.isEmpty()) {
            sb.append(" AND 1 = 0"); // no active profile → nothing to show
        } else {
            sb.append(" AND uj.profile IN (:activeNames)");
            params.put("activeNames", activeNames);
        }
        if (f.location() != null && !"location".equals(skip)) {
            sb.append(" AND j.location = :location");
            params.put("location", f.location());
        }
        if (f.remote() != null && !"remote".equals(skip)) {
            sb.append(" AND j.remote = :remote");
            params.put("remote", f.remote() ? 1 : 0);
        }
        if (f.profile() != null && !"profile".equals(skip)) {
            sb.append(" AND uj.profile = :profile");
            params.put("profile", f.profile());
        }
        if (f.source() != null && !"source".equals(skip)) {
            sb.append(" AND j.source = :source");
            params.put("source", f.source());
        }
        if (f.applied() != null && !"applied".equals(skip)) {
            sb.append(f.applied() ? " AND uj.applied_at IS NOT NULL" : " AND uj.applied_at IS NULL");
        }
        return sb.toString();
    }

    /** The user's matching jobs, newest first, limited. */
    public List<JobRow> filteredJobs(long userId, JobsFilter f, int limit) {
        var params = new LinkedHashMap<String, Object>();
        String where = buildWhere(userId, f, null, params);
        params.put("limit", limit);
        return bind(jdbc.sql("""
                SELECT j.id, j.title, j.company, j.location, j.remote, j.source, uj.profile, uj.score,
                       j.apply_url, COALESCE(uj.notified_at, j.posted_at, j.fetched_at) AS whn,
                       j.posted_at, uj.applied_at
                """ + where + """
                 ORDER BY datetime(COALESCE(j.posted_at, j.fetched_at)) DESC, j.id DESC
                 LIMIT :limit
                """), params)
                .query((rs, n) -> new JobRow(
                        rs.getLong("id"), rs.getString("title"), rs.getString("company"),
                        rs.getString("location"), rs.getInt("remote") != 0, rs.getString("source"),
                        rs.getString("profile"), (Integer) rs.getObject("score"),
                        rs.getString("apply_url"), rs.getString("whn"), rs.getString("posted_at"),
                        rs.getString("applied_at") != null))
                .list();
    }

    public long filteredCount(long userId, JobsFilter f) {
        var params = new LinkedHashMap<String, Object>();
        String where = buildWhere(userId, f, null, params);
        Long n = bind(jdbc.sql("SELECT COUNT(*) " + where), params).query(Long.class).single();
        return n == null ? 0 : n;
    }

    /** Facet counts for location/profile/source, honoring the other active filters. */
    public List<LabelCount> facet(long userId, String column, JobsFilter f) {
        var params = new LinkedHashMap<String, Object>();
        String where = buildWhere(userId, f, column, params);
        String expr = "location".equals(column) ? "COALESCE(j.location, '(unknown)')"
                : "source".equals(column) ? "j.source"
                : "uj.profile";
        return bind(jdbc.sql("SELECT " + expr + " AS label, COUNT(*) AS c " + where
                + " GROUP BY label ORDER BY c DESC LIMIT 30"), params)
                .query(AnalyticsRepository::labelCount).list();
    }

    /** Remote-vs-onsite facet counts, honoring the other active filters. */
    public List<LabelCount> remoteFacet(long userId, JobsFilter f) {
        var params = new LinkedHashMap<String, Object>();
        String where = buildWhere(userId, f, "remote", params);
        return bind(jdbc.sql("SELECT CASE WHEN j.remote != 0 THEN 'Remote' ELSE 'On-site' END AS label, "
                + "COUNT(*) AS c " + where + " GROUP BY label ORDER BY c DESC"), params)
                .query(AnalyticsRepository::labelCount).list();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (var e : params.entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec;
    }

    private long count(String sql, long userId) {
        Long n = jdbc.sql(sql).param("u", userId).query(Long.class).single();
        return n == null ? 0 : n;
    }

    private List<LabelCount> grouped(String sql, long userId) {
        return jdbc.sql(sql).param("u", userId).query(AnalyticsRepository::labelCount).list();
    }

    private static LabelCount labelCount(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LabelCount(rs.getString("label"), rs.getLong("c"));
    }
}
